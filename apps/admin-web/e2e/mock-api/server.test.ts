// @vitest-environment node
import { request, type IncomingHttpHeaders } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { EnrollmentResponse } from "@confia/contracts/admin";
import { findLeaks, makeSentinel } from "../support/sentinels.ts";
import { base32Decode, totpCode } from "../support/totp.ts";
import { CSRF_COOKIE, SESSION_COOKIE } from "./session-model.ts";
import { createMockServer, SCENARIO_HEADER, type MockServer } from "./server.ts";

// admin-web-foundations-and-session, task 11.1 and 11.2 (design.md D15; SS118, I306). The simulated API
// over a real socket, with a Node client playing the browser. It is a simulation of the shapes the backend
// publishes: nothing here is evidence about the real stack.
const ORIGIN = "http://localhost:5173";
const LIMIT = 120_000;

interface Reply {
  readonly status: number;
  readonly headers: IncomingHttpHeaders;
  readonly text: string;
  readonly json: unknown;
  readonly cookies: readonly string[];
}

let server: MockServer;
let port = 0;
let logs: string[] = [];
let slept: number[] = [];

beforeEach(async () => {
  logs = [];
  slept = [];
  server = createMockServer({
    log: (line) => logs.push(line),
    sleep: (ms) => {
      slept.push(ms);
      return Promise.resolve();
    },
  });
  port = await server.listen(0);
});

afterEach(async () => {
  await server.close();
});

function call(
  method: string,
  path: string,
  options: { headers?: Record<string, string>; body?: unknown; scenario?: string } = {},
): Promise<Reply> {
  const payload = options.body === undefined ? undefined : JSON.stringify(options.body);
  const headers: Record<string, string> = { ...options.headers };
  if (options.scenario !== undefined) headers[SCENARIO_HEADER] = options.scenario;
  if (payload !== undefined) headers["Content-Type"] = "application/json";
  return new Promise((resolve, reject) => {
    const req = request({ host: "localhost", port, method, path, headers }, (res) => {
      const chunks: Buffer[] = [];
      res.on("data", (chunk: Buffer) => chunks.push(chunk));
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        let json: unknown;
        try {
          json = text === "" ? undefined : JSON.parse(text);
        } catch {
          json = undefined;
        }
        resolve({ status: res.statusCode ?? 0, headers: res.headers, text, json, cookies: res.headers["set-cookie"] ?? [] });
      });
    });
    req.on("error", reject);
    req.end(payload);
  });
}

const typeOf = (reply: Reply) => (reply.json as { type: string }).type.split("/").pop();
const cookieOf = (reply: Reply, name: string) => {
  const line = reply.cookies.find((cookie) => cookie.startsWith(`${name}=`)) ?? "";
  return line.slice(name.length + 1, line.indexOf(";"));
};
const credentials = { identifier: "docente.prueba@example.test", password: makeSentinel("password") };

async function preset(id: string, name: string): Promise<void> {
  const reply = await call("POST", `/__control/scenarios/${id}`, { body: { preset: name } });
  expect(reply.status).toBe(200);
}

function cookieRoute(reply: Reply): Record<string, string> {
  return {
    Origin: ORIGIN,
    Cookie: `${SESSION_COOKIE}=${cookieOf(reply, SESSION_COOKIE)}; ${CSRF_COOKIE}=${cookieOf(reply, CSRF_COOKIE)}`,
    "X-CSRF-Token": cookieOf(reply, CSRF_COOKIE),
  };
}

describe("the access journey", { timeout: LIMIT }, () => {
  it("signs in, reads the session, refreshes with the double-submit check and signs out", async () => {
    const signedIn = await call("POST", "/api/v1/auth/sessions", { body: credentials });
    expect(signedIn.status).toBe(200);
    expect(signedIn.json).toMatchObject({ status: "authenticated" });
    expect(signedIn.cookies).toHaveLength(2);

    const bearer = { Authorization: `Bearer ${(signedIn.json as { accessToken: string }).accessToken}` };
    expect((await call("GET", "/api/v1/auth/sessions/current", { headers: bearer })).status).toBe(200);

    const refreshed = await call("POST", "/api/v1/auth/sessions/refresh", { headers: cookieRoute(signedIn) });
    expect(refreshed.status).toBe(200);
    expect(cookieOf(refreshed, SESSION_COOKIE)).not.toBe(cookieOf(signedIn, SESSION_COOKIE));

    const ended = await call("DELETE", "/api/v1/auth/sessions/current", { headers: cookieRoute(refreshed) });
    expect(ended.status).toBe(204);
    expect(ended.text).toBe("");
    expect(ended.cookies).toHaveLength(2);
    expect((await call("GET", "/api/v1/auth/sessions/current", { headers: bearer })).status).toBe(401);
  });

  it("refuses a refresh without the page Origin, with the same 403 the backend gives", async () => {
    const signedIn = await call("POST", "/api/v1/auth/sessions", { body: credentials });
    const withoutOrigin = Object.fromEntries(Object.entries(cookieRoute(signedIn)).filter(([name]) => name !== "Origin"));

    const reply = await call("POST", "/api/v1/auth/sessions/refresh", { headers: withoutOrigin });

    expect(reply.status).toBe(403);
    expect(typeOf(reply)).toBe("csrf-invalid");
  });

  it("answers an error as the problem media type with a relative instance, as the backend emits it", async () => {
    const reply = await call("POST", "/api/v1/auth/sessions/refresh", { headers: { Origin: ORIGIN } });

    expect(reply.status).toBe(401);
    expect(reply.headers["content-type"]).toContain("application/problem+json");
    expect((reply.json as { instance: string }).instance.startsWith("/")).toBe(true);
  });

  it("answers 404 resource-not-found for a route it does not simulate", async () => {
    const reply = await call("GET", "/api/v1/unknown");

    expect(reply.status).toBe(404);
    expect(typeOf(reply)).toBe("resource-not-found");
  });

  it("completes the second factor with a code computed from the secret it handed out", async () => {
    await preset("mfa", "enrollment-required");
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "mfa" });
    expect(login.json).toMatchObject({ status: "second-factor-enrollment-required" });
    const bearer = { Authorization: `Bearer ${(login.json as { secondFactorToken: string }).secondFactorToken}` };

    const enrolled = await call("POST", "/api/v1/auth/mfa/enrollments", { headers: bearer, scenario: "mfa" });
    expect(enrolled.status).toBe(201);
    const secret = base32Decode((enrolled.json as { secret: string }).secret);

    const done = await call("POST", "/api/v1/auth/mfa/verifications", {
      headers: bearer,
      body: { code: totpCode(secret, Date.now() / 1000) },
      scenario: "mfa",
    });
    expect(done.status).toBe(200);
    expect(done.cookies).toHaveLength(2);
  });
});

describe("the scenarios", { timeout: LIMIT }, () => {
  it.each([
    ["authentication-failed", 401, "authentication-failed"],
    ["too-many-requests", 429, "too-many-requests"],
    ["capacity-exceeded", 503, "capacity-exceeded"],
  ] as const)("the preset %s answers %i %s on the sign in", async (name, status, code) => {
    await preset(name, name);

    const reply = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: name });

    expect([reply.status, typeOf(reply)]).toEqual([status, code]);
  });

  it("SS41: the preset validation-failed answers 400 with the password refused for its length, and the answer meets the contract", async () => {
    await preset("refused", "validation-failed");

    const reply = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "refused" });

    expect([reply.status, typeOf(reply)]).toEqual([400, "validation-failed"]);
    expect((reply.json as { errors: unknown }).errors).toEqual([{ field: "password", reason: "max" }]);
    expect(reply.text).not.toContain(credentials.password);
    expect((await call("GET", "/__control/violations")).json).toEqual([]);
  });

  it("SS50: the preset internal-error answers 500 with a trace id, and the answer meets the contract", async () => {
    await preset("broken-down", "internal-error");

    const reply = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "broken-down" });

    expect([reply.status, typeOf(reply)]).toEqual([500, "internal-error"]);
    expect((reply.json as { traceId?: string }).traceId).toMatch(/^[A-Z0-9]{8,32}$/);
    expect((await call("GET", "/__control/violations")).json).toEqual([]);
  });

  it("gives Retry-After with the 429", async () => {
    await preset("busy", "too-many-requests");

    const reply = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "busy" });

    expect(reply.headers["retry-after"]).toBe("30");
  });

  it("answers 400 validation-failed with field errors for a body the contract refuses", async () => {
    const reply = await call("POST", "/api/v1/auth/sessions", { body: { identifier: "someone" } });

    expect(reply.status).toBe(400);
    expect(typeOf(reply)).toBe("validation-failed");
    expect((reply.json as { errors: unknown[] }).errors.length).toBeGreaterThan(0);
  });

  it("answers the slow preset after waiting 31 seconds, which the test only observes", async () => {
    await preset("slow", "slow");

    const reply = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "slow" });

    expect(slept).toEqual([31_000]);
    expect(reply.status).toBe(200);
  });

  it("issues a restricted token that is already expired under restricted-expired", async () => {
    await preset("late", "restricted-expired");
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "late" });
    const bearer = { Authorization: `Bearer ${(login.json as { secondFactorToken: string }).secondFactorToken}` };

    const reply = await call("POST", "/api/v1/auth/mfa/verifications", { headers: bearer, body: { code: "123456" }, scenario: "late" });

    expect([reply.status, typeOf(reply)]).toEqual([401, "token-expired"]);
  });

  it("answers 409 mfa-already-enrolled under already-enrolled", async () => {
    await preset("again", "already-enrolled");
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "again" });
    const bearer = { Authorization: `Bearer ${(login.json as { secondFactorToken: string }).secondFactorToken}` };

    const reply = await call("POST", "/api/v1/auth/mfa/enrollments", { headers: bearer, scenario: "again" });

    expect([reply.status, typeOf(reply)]).toEqual([409, "mfa-already-enrolled"]);
  });

  it("keeps each scenario apart: a session of one is unknown to another", async () => {
    const inA = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "a" });

    const inB = await call("POST", "/api/v1/auth/sessions/refresh", { headers: cookieRoute(inA), scenario: "b" });
    const inAAgain = await call("POST", "/api/v1/auth/sessions/refresh", { headers: cookieRoute(inA), scenario: "a" });

    expect([inB.status, inAAgain.status]).toEqual([401, 200]);
  });

  it("refuses a control call for a preset it does not have", async () => {
    const reply = await call("POST", "/__control/scenarios/x", { body: { preset: "does-not-exist" } });

    expect(reply.status).toBe(400);
  });
});

describe("SS118, I306: a response that breaks the contract", { timeout: LIMIT }, () => {
  it("never leaves the server: the client gets a 500 and the violation is kept, naming the field and never the value", async () => {
    await preset("broken", "contract-violation");
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "broken" });
    const bearer = { Authorization: `Bearer ${(login.json as { secondFactorToken: string }).secondFactorToken}` };

    const reply = await call("POST", "/api/v1/auth/mfa/enrollments", { headers: bearer, scenario: "broken" });
    const violations = await call("GET", "/__control/violations");

    expect(reply.status).toBe(500);
    expect(typeOf(reply)).toBe("internal-error");
    expect(reply.text).not.toContain("too-short");
    expect(violations.json).toEqual([{ operation: "enroll", status: 201, fields: ["secret"] }]);
    expect(violations.text).not.toContain("too-short");
  });

  it("holds no violation after answers that meet the contract", async () => {
    await call("POST", "/api/v1/auth/sessions", { body: credentials });

    expect((await call("GET", "/__control/violations")).json).toEqual([]);
  });
});

describe("the control", { timeout: LIMIT }, () => {
  it("answers on health and records the requests of a scenario without headers, bodies or tokens", async () => {
    expect((await call("GET", "/__control/health")).status).toBe(200);
    const password = makeSentinel("password");
    const token = makeSentinel("token");
    await call("POST", "/api/v1/auth/sessions", { body: { identifier: "docente.prueba@example.test", password }, scenario: "rec" });
    await call("GET", "/api/v1/auth/sessions/current", { headers: { Authorization: `Bearer ${token}` }, scenario: "rec" });

    const recorded = await call("GET", "/__control/requests/rec");

    expect(recorded.json).toEqual([
      { method: "POST", path: "/api/v1/auth/sessions" },
      { method: "GET", path: "/api/v1/auth/sessions/current" },
    ]);
    expect(findLeaks(recorded.text + logs.join("\n"), { password, token })).toEqual([]);
  });

  it("writes one line per request with the method, the path and the status, and nothing else", async () => {
    await call("POST", "/api/v1/auth/sessions?debug=1", { body: credentials });

    expect(logs).toEqual(["POST /api/v1/auth/sessions 200"]);
  });

  it("answers only on a local host name: another Host header gets 403 and no cookie", async () => {
    for (const host of ["evil.example", "localhost.evil.example", "192.0.2.10"]) {
      const reply = await call("GET", "/__control/seed-session", { headers: { Host: host } });

      expect([host, reply.status, reply.cookies.length]).toEqual([host, 403, 0]);
    }
    expect((await call("GET", "/__control/violations", { headers: { Host: "evil.example" } })).status).toBe(403);
  });

  it("listens on the loopback address and never on all interfaces", () => {
    const address = server.address() as AddressInfo;

    expect(["127.0.0.1", "::1"]).toContain(address.address);
  });
});

describe("the seeded test session", { timeout: LIMIT }, () => {
  it("sends the browser back to the page with the two cookies, the attributes of WE62 and WE63", async () => {
    const seeded = await call("GET", "/__control/seed-session");

    expect(seeded.status).toBe(303);
    expect(seeded.headers.location).toBe(`${ORIGIN}/`);
    expect(seeded.cookies).toEqual([
      `${SESSION_COOKIE}=${cookieOf(seeded, SESSION_COOKIE)}; Path=/api/v1/auth/sessions; HttpOnly; Secure; SameSite=Strict`,
      `${CSRF_COOKIE}=${cookieOf(seeded, CSRF_COOKIE)}; Path=/; Secure; SameSite=Strict`,
    ]);
    expect(cookieOf(seeded, SESSION_COOKIE)).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it("lets the page restore that session, as a fictitious account that is no real person", async () => {
    const seeded = await call("GET", "/__control/seed-session");

    const refreshed = await call("POST", "/api/v1/auth/sessions/refresh", { headers: cookieRoute(seeded) });
    const current = await call("GET", "/api/v1/auth/sessions/current", {
      headers: { Authorization: `Bearer ${(refreshed.json as { accessToken: string }).accessToken}` },
    });

    expect(refreshed.status).toBe(200);
    expect(Object.keys(current.json as object).sort()).toEqual([
      "accessTokenExpiresAt",
      "accountId",
      "authenticationMethods",
      "idleExpiresAt",
      "institutionId",
      "sessionExpiresAt",
      "sessionId",
    ]);
  });

  it("seeds the scenario the query names, so a journey can keep its own", async () => {
    const seeded = await call("POST", "/__control/seed-session?scenario=mine");

    expect((await call("POST", "/api/v1/auth/sessions/refresh", { headers: cookieRoute(seeded), scenario: "mine" })).status).toBe(200);
    expect((await call("POST", "/api/v1/auth/sessions/refresh", { headers: cookieRoute(seeded) })).status).toBe(401);
  });
});

describe("the account control of the fictitious test account [I304]", { timeout: LIMIT }, () => {
  const keyOf = (reply: Reply) => base32Decode((reply.json as { secret: string }).secret);

  it("answers the key and the recovery codes of the model, in the shape of the enrollment, and nothing else", async () => {
    const reply = await call("GET", "/__control/account/mine");

    expect(reply.status).toBe(200);
    expect(reply.headers["cache-control"]).toBe("no-store");
    expect(Object.keys(reply.json as object).sort()).toEqual(["recoveryCodes", "secret"]);
    expect(EnrollmentResponse.safeParse(reply.json).success).toBe(true);
    expect((reply.json as { recoveryCodes: string[] }).recoveryCodes).toHaveLength(10);
  });

  it("gives the key the model accepts: a code computed from it completes the second factor", async () => {
    await preset("acct", "second-factor-required");
    const account = await call("GET", "/__control/account/acct");
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "acct" });
    const bearer = { Authorization: `Bearer ${(login.json as { secondFactorToken: string }).secondFactorToken}` };

    const done = await call("POST", "/api/v1/auth/mfa/verifications", {
      headers: bearer,
      body: { code: totpCode(keyOf(account), Date.now() / 1000) },
      scenario: "acct",
    });

    expect(done.status).toBe(200);
  });

  it("lists only the recovery codes still valid, and keeps one account for each scenario", async () => {
    await preset("used", "second-factor-required");
    const before = (await call("GET", "/__control/account/used")).json as { recoveryCodes: string[] };
    const [spent] = before.recoveryCodes;
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "used" });
    const bearer = { Authorization: `Bearer ${(login.json as { secondFactorToken: string }).secondFactorToken}` };
    await call("POST", "/api/v1/auth/mfa/verifications", { headers: bearer, body: { recoveryCode: spent }, scenario: "used" });

    const after = (await call("GET", "/__control/account/used")).json as { recoveryCodes: string[] };
    const other = (await call("GET", "/__control/account/other")).json as { secret: string; recoveryCodes: string[] };

    expect(after.recoveryCodes).toHaveLength(9);
    expect(after.recoveryCodes).not.toContain(spent);
    expect(other.recoveryCodes).not.toContain(spent);
  });

  it("answers only on a local host name: another Host header gets 403 and no key or code", async () => {
    for (const host of ["evil.example", "localhost.evil.example", "192.0.2.10"]) {
      const reply = await call("GET", "/__control/account/mine", { headers: { Host: host } });

      expect([host, reply.status, "secret" in (reply.json as object)]).toEqual([host, 403, false]);
    }
  });

  it("refuses an id that is not a scenario id and a method other than GET", async () => {
    expect((await call("GET", "/__control/account/not%20valid")).status).toBe(404);
    expect((await call("POST", "/__control/account/mine")).status).toBe(404);
  });
});

describe("the academic structure [organization-academic-structure]", { timeout: LIMIT }, () => {
  const BASE = "/api/v1/organization";

  /** Signs in on a scenario (after the preset, if any) and answers the call helper bound to its bearer token. */
  async function session(id: string, name?: string) {
    if (name !== undefined) await preset(id, name);
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: id });
    const headers = { Authorization: `Bearer ${(login.json as { accessToken: string }).accessToken}` };
    return (method: string, path: string, body?: unknown) =>
      call(method, `${BASE}${path}`, { headers, scenario: id, ...(body === undefined ? {} : { body }) });
  }

  const items = (reply: Reply) => (reply.json as { items: { id: string; name: string; code?: string; ordinal?: number }[] }).items;
  const page = (reply: Reply) => reply.json as { items: unknown[]; page: number; size: number; total: number };
  const names = (reply: Reply) => items(reply).map((row) => row.name);
  const year = (name: string, startDate: string, endDate: string, extra: object = {}) => ({ name, startDate, endDate, ...extra });
  const verdict = (reply: Reply) => [reply.status, typeOf(reply)];

  it("answers 401 to every operation without an access token, and 404 to a path it does not simulate", async () => {
    const anonymous = [
      await call("GET", `${BASE}/academic-years`, { scenario: "org-401" }),
      await call("POST", `${BASE}/modalities`, { scenario: "org-401", body: { code: "X", name: "X" } }),
    ];
    const send = await session("org-404");

    expect(anonymous.map(verdict)).toEqual([
      [401, "authentication-required"],
      [401, "authentication-required"],
    ]);
    expect((await send("GET", "/unknown")).status).toBe(404);
    expect((await send("DELETE", "/academic-years/x")).status).toBe(404);
  });

  it("lists the seeded rows by page, newest year first, with `total` counting every page", async () => {
    const send = await session("org-page");

    const first = await send("GET", "/academic-years?page=0&size=25");
    const second = await send("GET", "/academic-years?page=1&size=25");

    expect(page(first)).toMatchObject({ page: 0, size: 25, total: 30 });
    expect(items(first)).toHaveLength(25);
    expect(names(first)[0]).toBe("Año lectivo 2026");
    expect(names(second)).toHaveLength(5);
    expect(names(second).at(-1)).toBe("Año lectivo 1997");
  });

  it("sorts on the server, in either direction, and refuses a field that is not allowed, a bad page or a bad size", async () => {
    const send = await session("org-sort");

    expect(names(await send("GET", "/academic-years?sort=name,asc&size=2"))).toEqual(["Año lectivo 1997", "Año lectivo 1998"]);
    expect(names(await send("GET", "/sections?sort=name,desc&size=1"))).toEqual(["B"]);
    for (const query of ["sort=isActive,asc", "sort=name,sideways", "page=-1", "size=0", "size=201"]) {
      const reply = await send("GET", `/modalities?${query}`);
      expect([query, ...verdict(reply)]).toEqual([query, 400, "validation-failed"]);
    }
  });

  it("filters the grades by modality and the sections by year and grade, on the server", async () => {
    const send = await session("org-filter");
    const primary = items(await send("GET", "/modalities")).find((row) => row.code === "PRI")?.id ?? "";
    const grades = items(await send("GET", `/grades?modalityId=${primary}`));
    const first = grades.find((row) => row.name === "Primero")?.id ?? "";
    const last = items(await send("GET", "/academic-years")).find((row) => row.name === "Año lectivo 2026")?.id ?? "";

    expect(grades.map((row) => row.name).sort()).toEqual(["Primero", "Segundo", "Tercero"]);
    expect(page(await send("GET", `/sections?academicYearId=${last}`)).total).toBe(3);
    expect(page(await send("GET", `/sections?academicYearId=${last}&gradeId=${first}`)).total).toBe(2);
    expect(page(await send("GET", `/sections?gradeId=${first}`)).total).toBe(3);
  });

  it("creates with 201 and the full row, lists it, and edits with 200, keeping what is fixed at creation", async () => {
    const send = await session("org-write", "organization-empty");
    expect(page(await send("GET", "/modalities")).total).toBe(0);

    const created = await send("POST", "/modalities", { code: "PRI", name: "Primaria" });
    const id = (created.json as { id: string }).id;
    const grade = await send("POST", "/grades", { modalityId: id, name: "Primero", ordinal: 1 });
    const edited = await send("PUT", `/modalities/${id}`, { code: "PRI", name: "Primaria básica", isActive: false });
    const gradeEdit = await send("PUT", `/grades/${(grade.json as { id: string }).id}`, { name: "Primero A", ordinal: 1, isActive: true });

    expect(created.status).toBe(201);
    expect(created.json).toMatchObject({ code: "PRI", name: "Primaria", isActive: true });
    expect(grade.status).toBe(201);
    expect(edited.status).toBe(200);
    expect(edited.json).toMatchObject({ id, name: "Primaria básica", isActive: false });
    expect(names(await send("GET", "/modalities"))).toEqual(["Primaria básica"]);
    expect(gradeEdit.json).toMatchObject({ modalityId: id, name: "Primero A" });
  });

  it("starts an empty scenario with no rows, and gives a new year the status PLANNED and a section with no capacity null", async () => {
    const send = await session("org-empty", "organization-empty");
    const created = await send("POST", "/academic-years", year("Año lectivo 2027", "2027-01-15", "2027-11-30"));
    const modality = await send("POST", "/modalities", { code: "PRI", name: "Primaria" });
    const grade = await send("POST", "/grades", { modalityId: (modality.json as { id: string }).id, name: "Primero", ordinal: 1 });
    const section = await send("POST", "/sections", {
      academicYearId: (created.json as { id: string }).id,
      gradeId: (grade.json as { id: string }).id,
      name: "A",
    });

    expect(created.json).toMatchObject({ status: "PLANNED" });
    expect(section.status).toBe(201);
    expect(section.json).toMatchObject({ name: "A", capacity: null });
  });

  it("refuses a year that overlaps another by one day, and accepts the one that starts the next day", async () => {
    const send = await session("org-overlap");

    const touching = await send("POST", "/academic-years", year("Año lectivo 2027", "2026-11-30", "2027-10-30"));
    const nextDay = await send("POST", "/academic-years", year("Año lectivo 2027", "2026-12-01", "2027-10-30"));

    expect(verdict(touching)).toEqual([409, "academic-year-overlap"]);
    expect(nextDay.status).toBe(201);
  });

  it("refuses a repeated name, code or order with duplicate-name, and a second year in progress with academic-year-already-active", async () => {
    const send = await session("org-duplicate");
    const modalityId = items(await send("GET", "/modalities")).find((row) => row.code === "PRI")?.id ?? "";

    const replies = [
      await send("POST", "/academic-years", year("Año lectivo 2026", "2030-01-15", "2030-11-30")),
      await send("POST", "/modalities", { code: "PRI", name: "Otra" }),
      await send("POST", "/grades", { modalityId, name: "Cuarto", ordinal: 1 }),
      await send("POST", "/grades", { modalityId, name: "Primero", ordinal: 9 }),
      await send("POST", "/academic-years", year("Año lectivo 2030", "2030-01-15", "2030-11-30", { status: "ACTIVE" })),
    ];

    expect(replies.map(verdict)).toEqual([
      [409, "duplicate-name"],
      [409, "duplicate-name"],
      [409, "duplicate-name"],
      [409, "duplicate-name"],
      [409, "academic-year-already-active"],
    ]);
  });

  it("does not edit a closed year, nor create a section in one, and answers 404 for a row that does not exist", async () => {
    const send = await session("org-closed");
    const closed = items(await send("GET", "/academic-years?sort=name,asc&size=1"))[0];
    const grade = items(await send("GET", "/grades")).find((row) => row.name === "Primero")?.id ?? "";

    const edit = await send("PUT", `/academic-years/${closed?.id ?? ""}`, { ...year("Otro nombre", "1997-01-15", "1997-11-30"), status: "CLOSED", isActive: true });
    const section = await send("POST", "/sections", { academicYearId: closed?.id, gradeId: grade, name: "Z" });
    const missing = await send("PUT", "/modalities/00000000-0000-4000-8000-999999999999", { code: "X", name: "X", isActive: true });

    expect(verdict(edit)).toEqual([409, "academic-year-closed"]);
    expect(verdict(section)).toEqual([409, "academic-year-closed"]);
    expect(verdict(missing)).toEqual([404, "resource-not-found"]);
  });

  it("refuses a grade under an inactive modality with inactive-reference", async () => {
    const send = await session("org-inactive");
    const modality = items(await send("GET", "/modalities")).find((row) => row.code === "PRE");
    await send("PUT", `/modalities/${modality?.id ?? ""}`, { code: "PRE", name: "Prebásica", isActive: false });

    const reply = await send("POST", "/grades", { modalityId: modality?.id, name: "Kinder", ordinal: 1 });

    expect(verdict(reply)).toEqual([409, "inactive-reference"]);
  });

  it("answers validation-failed with the fields at fault: an end that is not after the start, a blank name and a capacity of zero", async () => {
    const send = await session("org-validation");
    const ids = { academicYearId: "00000000-0000-4000-8000-000000000001", gradeId: "00000000-0000-4000-8000-000000000002" };

    const range = await send("POST", "/academic-years", year("Año lectivo 2031", "2031-05-01", "2031-05-01"));
    const blank = await send("POST", "/modalities", { code: "X", name: "  " });
    const capacity = await send("POST", "/sections", { ...ids, name: "A", capacity: 0 });

    expect(range.status).toBe(400);
    expect((range.json as { errors: unknown[] }).errors).toEqual([{ field: "endAfterStart", reason: "invalid" }]);
    expect((blank.json as { errors: unknown[] }).errors).toEqual([{ field: "name", reason: "required" }]);
    expect((capacity.json as { errors: { field: string }[] }).errors.map((error) => error.field)).toEqual(["capacity"]);
  });

  it("refuses a write with 403 forbidden and changes nothing, while the reads keep answering", async () => {
    const send = await session("org-forbidden", "organization-forbidden");

    const create = await send("POST", "/modalities", { code: "NEW", name: "Nueva" });
    const edit = await send("PUT", `/modalities/${items(await send("GET", "/modalities"))[0]?.id ?? ""}`, { code: "PRI", name: "x", isActive: false });

    expect(verdict(create)).toEqual([403, "forbidden"]);
    expect(verdict(edit)).toEqual([403, "forbidden"]);
    expect(names(await send("GET", "/modalities?sort=name,asc"))).toEqual(["Prebásica", "Primaria", "Secundaria"]);
  });

  it("fails the first three reads of a resource with 500 and a trace id, then recovers, each resource on its own count", async () => {
    const send = await session("org-server-error", "organization-server-error");

    const failed = [await send("GET", "/academic-years"), await send("GET", "/academic-years"), await send("GET", "/academic-years")];
    const other = await send("GET", "/modalities");
    const recovered = await send("GET", "/academic-years");

    expect(failed.map(verdict)).toEqual(Array(3).fill([500, "internal-error"]));
    expect(failed[0]?.json).toMatchObject({ traceId: "01J8Z4K2QF9P0X" });
    expect(other.status).toBe(500);
    expect(recovered.status).toBe(200);
  });

  it("keeps one academic structure for each scenario", async () => {
    const mine = await session("org-mine", "organization-empty");
    const theirs = await session("org-theirs");
    await mine("POST", "/modalities", { code: "ONE", name: "Una" });

    expect(page(await mine("GET", "/modalities")).total).toBe(1);
    expect(page(await theirs("GET", "/modalities")).total).toBe(3);
  });
});


describe("the students registry [students-registry]", { timeout: LIMIT }, () => {
  const BASE = "/api/v1/students";
  const DOCUMENT = "0801-2010-98765";
  const NEW_STUDENT = { firstName: "Marta", lastName: "Zúñiga", birthDate: "2012-05-20", sex: "F" };

  /** Signs in on a scenario (after the preset, if any) and answers the call helper bound to its bearer token. */
  async function session(id: string, name?: string) {
    if (name !== undefined) await preset(id, name);
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: id });
    const headers = { Authorization: `Bearer ${(login.json as { accessToken: string }).accessToken}` };
    return (method: string, path: string, body?: unknown) =>
      call(method, `${BASE}${path}`, { headers, scenario: id, ...(body === undefined ? {} : { body }) });
  }

  interface Row {
    id: string;
    studentCode: string;
    firstName: string;
    lastName: string;
    hasNationalId: boolean;
    nationalIdMasked: string | null;
  }
  const rows = (reply: Reply) => (reply.json as { items: Row[] }).items;
  const total = (reply: Reply) => (reply.json as { total: number }).total;
  const verdict = (reply: Reply) => [reply.status, typeOf(reply)];
  const fieldsOf = (reply: Reply) => (reply.json as { errors: { field: string; reason: string }[] }).errors;

  it("answers 401 to every operation without an access token, and 404 to a path it does not simulate", async () => {
    const anonymous = [await call("GET", BASE, { scenario: "stu-401" }), await call("POST", BASE, { scenario: "stu-401", body: NEW_STUDENT })];
    const send = await session("stu-404");

    expect(anonymous.map(verdict)).toEqual([
      [401, "authentication-required"],
      [401, "authentication-required"],
    ]);
    expect((await send("DELETE", "/x")).status).toBe(404);
    expect((await send("GET", "/x/y")).status).toBe(404);
  });

  it("lists the sixty seeded students by last name, with `total` counting every page, and pages them", async () => {
    const send = await session("stu-page");

    const first = await send("GET", "?page=0&size=25");
    const last = await send("GET", "?page=2&size=25");

    expect(total(first)).toBe(60);
    expect(rows(first)).toHaveLength(25);
    expect(rows(first)[0]).toMatchObject({ studentCode: "E-000001", lastName: "Aguilar" });
    expect(rows(last)).toHaveLength(10);
    expect(rows(last).at(-1)?.lastName).toBe("Zelaya");
  });

  it("sorts on the server in either direction and refuses a field that is not allowed, a bad page or a bad size", async () => {
    const send = await session("stu-sort");

    expect(rows(await send("GET", "?sort=lastName,desc&size=1")).map((row) => row.lastName)).toEqual(["Zelaya"]);
    expect(rows(await send("GET", "?sort=studentCode,desc&size=1")).map((row) => row.studentCode)).toEqual(["E-000060"]);
    for (const query of ["sort=nationalId,asc", "sort=lastName,sideways", "page=-1", "size=0", "size=201"]) {
      expect([query, ...verdict(await send("GET", `?${query}`))]).toEqual([query, 400, "validation-failed"]);
    }
  });

  it("searches name, last name and code without regard to capitals, and refuses a text under two characters, empty included", async () => {
    const send = await session("stu-search");

    expect(total(await send("GET", `?q=${encodeURIComponent("PÉREZ")}`))).toBe(2);
    expect(rows(await send("GET", "?q=e-000042")).map((row) => row.firstName)).toEqual(["Luis"]);
    expect(total(await send("GET", "?q=valeria"))).toBe(3);
    for (const query of ["q=a", "q=", "q=%20%20", `q=${"x".repeat(101)}`]) {
      const reply = await send("GET", `?${query}`);
      expect([query.slice(0, 6), ...verdict(reply)]).toEqual([query.slice(0, 6), 400, "validation-failed"]);
      expect(fieldsOf(reply).map((error) => error.field)).toEqual(["q"]);
    }
  });

  it("filters by status and refuses one it does not know", async () => {
    const send = await session("stu-status");

    expect(total(await send("GET", "?status=INACTIVE"))).toBe(6);
    expect(total(await send("GET", "?status=ACTIVE"))).toBe(54);
    expect(verdict(await send("GET", "?status=BORRADO"))).toEqual([400, "validation-failed"]);
  });

  it("registers a student with 201, generates the next code, masks the document, and never returns it", async () => {
    const send = await session("stu-create");

    const created = await send("POST", "", { ...NEW_STUDENT, nationalId: DOCUMENT });
    const found = await send("GET", `?q=${encodeURIComponent("Zúñiga")}`);

    expect(created.status).toBe(201);
    expect(created.json).toMatchObject({ studentCode: "E-000061", firstName: "Marta", status: "ACTIVE", hasNationalId: true, nationalIdMasked: "****8765" });
    expect(rows(found)).toHaveLength(1);
    expect(rows(found)[0]).toMatchObject({ hasNationalId: true, nationalIdMasked: "****8765" });
    for (const reply of [created, found]) {
      expect(reply.text).not.toContain(DOCUMENT);
      expect(reply.text).not.toContain("0801201098765");
      expect(reply.json).not.toHaveProperty("nationalId");
    }
    const without = await send("POST", "", { ...NEW_STUDENT, studentCode: " MI-1 " });
    expect(without.json).toMatchObject({ studentCode: "MI-1", hasNationalId: false, nationalIdMasked: null });
  });

  it("refuses a repeated code and a repeated national id, also with other hyphens or capitals, with 409", async () => {
    const send = await session("stu-duplicate");
    await send("POST", "", { ...NEW_STUDENT, nationalId: "ab-12-34-56-78" });

    const replies = [
      await send("POST", "", { ...NEW_STUDENT, studentCode: "E-000001" }),
      await send("POST", "", { ...NEW_STUDENT, nationalId: "AB12345678" }),
      await send("POST", "", { ...NEW_STUDENT, nationalId: "0801201210003" }),
    ];

    expect(replies.map(verdict)).toEqual([
      [409, "duplicate-student-code"],
      [409, "duplicate-national-id"],
      [409, "duplicate-national-id"],
    ]);
  });

  it("answers validation-failed with the fields at fault: names, birth date limits, sex, and the format of the document", async () => {
    const send = await session("stu-validation", "students-empty");

    const blank = await send("POST", "", { ...NEW_STUDENT, firstName: "  ", lastName: "x".repeat(101) });
    const early = await send("POST", "", { ...NEW_STUDENT, birthDate: "1989-12-31" });
    const future = await send("POST", "", { ...NEW_STUDENT, birthDate: "2999-01-01" });
    const format = await send("POST", "", { ...NEW_STUDENT, nationalId: "123 45" });
    const short = await send("POST", "", { ...NEW_STUDENT, nationalId: "1----2----" });
    const sex = await send("POST", "", { ...NEW_STUDENT, sex: "X" });

    expect(fieldsOf(blank)).toEqual([
      { field: "firstName", reason: "not-blank" },
      { field: "lastName", reason: "size" },
    ]);
    expect(fieldsOf(early)).toEqual([{ field: "birthDate", reason: "birth-date-floor" }]);
    expect(fieldsOf(future)).toEqual([{ field: "birthDate", reason: "past-or-present" }]);
    expect(fieldsOf(format)).toEqual([{ field: "nationalId", reason: "pattern" }]);
    expect(fieldsOf(short)).toEqual([{ field: "nationalId", reason: "pattern" }]);
    expect(fieldsOf(sex).map((error) => error.field)).toEqual(["sex"]);
  });

  it("refuses a member it does not know on a create, and the code on an edit", async () => {
    const send = await session("stu-unknown");
    const id = rows(await send("GET", "?size=1"))[0]?.id ?? "";
    const edit = { firstName: "A", lastName: "B", birthDate: "2012-05-20", sex: "F", status: "ACTIVE" };

    const create = await send("POST", "", { ...NEW_STUDENT, institutionId: "x" });
    const withCode = await send("PUT", `/${id}`, { ...edit, studentCode: "E-9" });

    expect(fieldsOf(create)).toEqual([{ field: "institutionId", reason: "unknown" }]);
    expect(fieldsOf(withCode)).toEqual([{ field: "studentCode", reason: "unknown" }]);
  });

  it("edits with 200: an omitted document is kept, an empty one removes it, any other replaces it, and the code stays", async () => {
    const send = await session("stu-edit");
    const target = rows(await send("GET", "?q=E-000042"))[0];
    const id = target?.id ?? "";
    const edit = { firstName: "Luis Alberto", lastName: "Pérez", birthDate: "2014-07-16", sex: "M", status: "ACTIVE" };
    expect(target).toMatchObject({ hasNationalId: true, nationalIdMasked: "****0042" });

    const kept = await send("PUT", `/${id}`, edit);
    const replaced = await send("PUT", `/${id}`, { ...edit, nationalId: DOCUMENT });
    const removed = await send("PUT", `/${id}`, { ...edit, nationalId: "" });

    expect(kept.json).toMatchObject({ studentCode: "E-000042", firstName: "Luis Alberto", hasNationalId: true, nationalIdMasked: "****0042" });
    expect(replaced.json).toMatchObject({ hasNationalId: true, nationalIdMasked: "****8765" });
    expect(removed.json).toMatchObject({ hasNationalId: false, nationalIdMasked: null });
    expect(replaced.text).not.toContain("98765");
  });

  it("inactivates with a PUT, deletes nothing, and a student may keep their own document in an edit", async () => {
    const send = await session("stu-inactivate");
    const target = rows(await send("GET", "?q=E-000042"))[0];
    const id = target?.id ?? "";
    const edit = { firstName: "Luis", lastName: "Pérez", birthDate: "2014-07-16", sex: "M", status: "INACTIVE" };

    const own = await send("PUT", `/${id}`, { ...edit, nationalId: "0801-2012-10042" });
    const missing = await send("PUT", "/00000000-0000-4000-8000-999999999999", edit);

    expect(own.json).toMatchObject({ status: "INACTIVE", hasNationalId: true });
    expect(verdict(missing)).toEqual([404, "resource-not-found"]);
    expect(total(await send("GET", "?status=INACTIVE"))).toBe(7);
    expect(total(await send("GET", ""))).toBe(60);
  });

  it("starts an empty scenario with no students", async () => {
    const send = await session("stu-empty", "students-empty");

    expect(total(await send("GET", ""))).toBe(0);
    expect((await send("POST", "", NEW_STUDENT)).json).toMatchObject({ studentCode: "E-000001" });
  });

  it("refuses a write with 403 forbidden and changes nothing, while the reads keep answering", async () => {
    const send = await session("stu-forbidden", "students-forbidden");
    const id = rows(await send("GET", "?size=1"))[0]?.id ?? "";

    const create = await send("POST", "", NEW_STUDENT);
    const edit = await send("PUT", `/${id}`, { firstName: "A", lastName: "B", birthDate: "2012-05-20", sex: "F", status: "INACTIVE" });

    expect(verdict(create)).toEqual([403, "forbidden"]);
    expect(verdict(edit)).toEqual([403, "forbidden"]);
    expect(total(await send("GET", ""))).toBe(60);
    expect(total(await send("GET", "?status=INACTIVE"))).toBe(6);
  });

  it("fails the first three reads with 500 and a trace id, then recovers", async () => {
    const send = await session("stu-server-error", "students-server-error");

    const failed = [await send("GET", ""), await send("GET", ""), await send("GET", "")];
    const recovered = await send("GET", "");

    expect(failed.map(verdict)).toEqual(Array(3).fill([500, "internal-error"]));
    expect(failed[0]?.json).toMatchObject({ traceId: "01J8Z4K2QF9P0X" });
    expect(recovered.status).toBe(200);
  });

  it("keeps one registry for each scenario", async () => {
    const mine = await session("stu-mine", "students-empty");
    const theirs = await session("stu-theirs");
    await mine("POST", "", NEW_STUDENT);

    expect(total(await mine("GET", ""))).toBe(1);
    expect(total(await theirs("GET", ""))).toBe(60);
  });
});
