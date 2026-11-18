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

  it("issues an enrollment token that is already expired under enrollment-expired", async () => {
    await preset("early", "enrollment-expired");
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "early" });
    const bearer = { Authorization: `Bearer ${(login.json as { secondFactorToken: string }).secondFactorToken}` };

    const reply = await call("POST", "/api/v1/auth/mfa/enrollments", { headers: bearer, scenario: "early" });

    expect([(login.json as { status: string }).status, reply.status, typeOf(reply)]).toEqual(["second-factor-enrollment-required", 401, "token-expired"]);
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

  it("two enrollments in a row answer different secrets and codes, and the account control follows the last one [SS100]", async () => {
    await preset("twice", "enrollment-required");
    const enroll = async () => {
      const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: "twice" });
      const bearer = { Authorization: `Bearer ${(login.json as { secondFactorToken: string }).secondFactorToken}` };
      return (await call("POST", "/api/v1/auth/mfa/enrollments", { headers: bearer, scenario: "twice" })).json as { secret: string; recoveryCodes: string[] };
    };

    const first = await enroll();
    const second = await enroll();
    const account = (await call("GET", "/__control/account/twice")).json as { secret: string; recoveryCodes: string[] };

    expect(second.secret).not.toBe(first.secret);
    expect(second.recoveryCodes.filter((code) => first.recoveryCodes.includes(code))).toEqual([]);
    expect(account).toEqual(second);
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

describe("the catalog [catalog-payment-concepts]", { timeout: LIMIT }, () => {
  const BASE = "/api/v1/catalog/payment-concepts";
  const RATE_A = "00000000-0000-4000-8000-000000006001";
  const NEW_CONCEPT = { code: "NUE", name: "Nuevo concepto", category: "OTHER", isRecurring: false, isTaxable: true, taxRateId: RATE_A };

  /** Signs in on a scenario (after the preset, if any) and answers the call helper bound to its bearer token. */
  async function session(id: string, name?: string) {
    if (name !== undefined) await preset(id, name);
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: id });
    const headers = { Authorization: `Bearer ${(login.json as { accessToken: string }).accessToken}` };
    return (method: string, path = "", body?: unknown) => call(method, `${BASE}${path}`, { headers, scenario: id, ...(body === undefined ? {} : { body }) });
  }

  interface Row {
    id: string;
    code: string;
    name: string;
    category: string;
    isActive: boolean;
  }
  const rows = (reply: Reply) => (reply.json as { items: Row[] }).items;
  const total = (reply: Reply) => (reply.json as { total: number }).total;
  const codes = (reply: Reply) => rows(reply).map((row) => row.code);
  const verdict = (reply: Reply) => [reply.status, typeOf(reply)];

  it("answers 401 to every operation without an access token, and 404 to a path it does not simulate", async () => {
    const anonymous = [await call("GET", BASE, { scenario: "cat-401" }), await call("POST", BASE, { scenario: "cat-401", body: NEW_CONCEPT })];
    const send = await session("cat-404");

    expect(anonymous.map(verdict)).toEqual([
      [401, "authentication-required"],
      [401, "authentication-required"],
    ]);
    expect((await send("DELETE", "/x")).status).toBe(404);
    expect((await send("GET", "/x/y")).status).toBe(404);
  });

  it("lists the thirty seeded concepts by code, without telling capitals apart, with `total` counting every page", async () => {
    const send = await session("cat-page");

    const first = await send("GET", "?page=0&size=25");
    const second = await send("GET", "?page=1&size=25");

    expect(total(first)).toBe(30);
    expect(codes(first).slice(0, 6)).toEqual(["COL", "LIB", "MAT", "MUL", "OTR", "TRA"]);
    expect(rows(first)).toHaveLength(25);
    expect(rows(second)).toHaveLength(5);
  });

  it("sorts on the server, in either direction, and refuses a field that is not allowed, a bad page, a bad size or a bad filter", async () => {
    const send = await session("cat-sort");

    expect(rows(await send("GET", "?sort=name,asc&size=2")).map((row) => row.name)).toEqual(["Colegiatura", "Concepto genérico 01"]);
    expect(codes(await send("GET", "?sort=code,desc&size=1"))).toEqual(["X-24"]);
    for (const query of ["sort=category,asc", "sort=code,sideways", "page=-1", "size=0", "size=201", "category=BOOKS", "active=maybe"]) {
      const reply = await send("GET", `?${query}`);
      expect([query, ...verdict(reply)]).toEqual([query, 400, "validation-failed"]);
    }
  });

  it("filters by category and by state on the server", async () => {
    const send = await session("cat-filter");

    expect(codes(await send("GET", "?category=FINE"))).toEqual(["MUL"]);
    expect(codes(await send("GET", "?active=false"))).toEqual(["OTR"]);
    expect(total(await send("GET", "?category=OTHER&active=true"))).toBe(24);
    expect(total(await send("GET", "?category=TUITION&active=false"))).toBe(0);
  });

  it("creates with 201, lists it, and edits with 200 without the code, keeping the code as it was created", async () => {
    const send = await session("cat-write", "catalog-empty");
    expect(total(await send("GET"))).toBe(0);

    const created = await send("POST", "", NEW_CONCEPT);
    const id = (created.json as { id: string }).id;
    const edited = await send("PUT", `/${id}`, { version: 0, name: "Concepto editado", category: "FINE", isRecurring: true, isTaxable: false, isActive: false });

    expect(created.status).toBe(201);
    expect(created.json).toMatchObject({
      code: "NUE",
      name: "Nuevo concepto",
      category: "OTHER",
      isTaxable: true,
      isActive: true,
      taxRateId: RATE_A,
      taxRateCode: "IMP-A",
      taxRateName: "Impuesto de prueba A",
    });
    expect(edited.status).toBe(200);
    expect(created.json).toMatchObject({ version: 0 });
    expect(edited.json).toMatchObject({ id, code: "NUE", name: "Concepto editado", category: "FINE", isRecurring: true, isActive: false, version: 1, taxRateId: null, taxRateCode: null });
    expect(rows(await send("GET")).map((row) => row.name)).toEqual(["Concepto editado"]);
  });

  it("refuses a repeated code whatever its capitals with 409 duplicate-payment-concept-code, and an edit that sends the code or targets a missing concept", async () => {
    const send = await session("cat-rules");
    const id = rows(await send("GET")).find((row) => row.code === "COL")?.id ?? "";
    const edit = { version: 0, name: "x", category: "TUITION", isRecurring: true, isTaxable: true, taxRateId: RATE_A, isActive: true };

    const repeated = await send("POST", "", { ...NEW_CONCEPT, code: "col" });
    const withCode = await send("PUT", `/${id}`, { ...edit, code: "NEW" });
    const missing = await send("PUT", "/00000000-0000-4000-8000-000000000001", edit);

    expect(verdict(repeated)).toEqual([409, "duplicate-payment-concept-code"]);
    expect(verdict(withCode)).toEqual([400, "validation-failed"]);
    expect(verdict(missing)).toEqual([404, "resource-not-found"]);
  });

  it("refuses a body that breaks the contract with 400 and the fields", async () => {
    const send = await session("cat-invalid");

    const category = await send("POST", "", { ...NEW_CONCEPT, category: "BOOKS" });
    const blank = await send("POST", "", { ...NEW_CONCEPT, name: "  " });
    const long = await send("POST", "", { ...NEW_CONCEPT, code: "C".repeat(31) });

    expect((category.json as { errors: { field: string }[] }).errors.map((error) => error.field)).toEqual(["category"]);
    expect((blank.json as { errors: unknown[] }).errors).toEqual([{ field: "name", reason: "required" }]);
    expect(verdict(long)).toEqual([400, "validation-failed"]);
  });

  it("refuses a code outside the whitelist, or a name with a control character, with the reason pattern", async () => {
    const send = await session("cat-pattern");

    const codes = [
      await send("POST", "", { ...NEW_CONCEPT, code: "CO L" }),
      await send("POST", "", { ...NEW_CONCEPT, code: "-COL" }),
      await send("POST", "", { ...NEW_CONCEPT, code: "CÓL" }),
    ];
    const name = await send("POST", "", { ...NEW_CONCEPT, name: "Nuevo\u0000concepto" });
    const accepted = await send("POST", "", { ...NEW_CONCEPT, code: "A_b-9" });

    for (const reply of codes) expect((reply.json as { errors: unknown[] }).errors).toEqual([{ field: "code", reason: "pattern" }]);
    expect((name.json as { errors: unknown[] }).errors).toEqual([{ field: "name", reason: "pattern" }]);
    expect(accepted.status).toBe(201);
  });

  it("refuses a write with 403 forbidden and changes nothing, while the reads keep answering", async () => {
    const send = await session("cat-forbidden", "catalog-forbidden");

    const create = await send("POST", "", NEW_CONCEPT);

    expect(verdict(create)).toEqual([403, "forbidden"]);
    expect(total(await send("GET"))).toBe(30);
  });

  it("fails the first three reads with 500 and a trace id, then recovers", async () => {
    const send = await session("cat-server-error", "catalog-server-error");

    const failed = [await send("GET"), await send("GET"), await send("GET")];
    const recovered = await send("GET");

    expect(failed.map(verdict)).toEqual(Array(3).fill([500, "internal-error"]));
    expect(failed[0]?.json).toMatchObject({ traceId: "01J8Z4K2QF9P0X" });
    expect(recovered.status).toBe(200);
  });

  it("keeps one catalog for each scenario", async () => {
    const mine = await session("cat-mine", "catalog-empty");
    const theirs = await session("cat-theirs");
    await mine("POST", "", NEW_CONCEPT);

    expect(total(await mine("GET"))).toBe(1);
    expect(total(await theirs("GET"))).toBe(30);
  });
});

describe("the catalog versions and the price lists [catalog-price-lists]", { timeout: LIMIT }, () => {
  const CONCEPTS = "/api/v1/catalog/payment-concepts";
  const LISTS = "/api/v1/catalog/price-lists";
  const YEAR = "00000000-0000-4000-8000-000000001030";
  const COL = "00000000-0000-4000-8000-000000007001";
  const MUL = "00000000-0000-4000-8000-000000007005";
  const OTR = "00000000-0000-4000-8000-000000007006";
  const SEEDED_LIST = "00000000-0000-4000-8000-000000009001";
  const ARCHIVED_LIST = "00000000-0000-4000-8000-000000009003";
  const NEW_LIST = { academicYearId: YEAR, name: "2027 — Institución", scope: "INSTITUTION" };
  const edit = { version: 0, name: "x", category: "TUITION", isRecurring: true, isTaxable: true, taxRateId: "00000000-0000-4000-8000-000000006001", isActive: true };

  /** Signs in on a scenario (after the preset, if any) and answers the call helper bound to its bearer token. */
  async function session(id: string, name?: string) {
    if (name !== undefined) await preset(id, name);
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: id });
    const headers = { Authorization: `Bearer ${(login.json as { accessToken: string }).accessToken}` };
    return (method: string, path: string, body?: unknown) => call(method, path, { headers, scenario: id, ...(body === undefined ? {} : { body }) });
  }

  interface ListRow {
    id: string;
    name: string;
    status: string;
    scope: string;
    version: number;
  }
  interface ItemRow {
    id: string;
    paymentConceptId: string;
    price: { amount: string; currency: string };
    validFrom: string;
    validTo: string | null;
  }
  const names = (reply: Reply) => (reply.json as { items: ListRow[] }).items.map((row) => row.name);
  const total = (reply: Reply) => (reply.json as { total: number }).total;
  const verdict = (reply: Reply) => [reply.status, typeOf(reply)];
  const price = (concept: string, amount: string, validFrom: string, validTo?: string, currency = "HNL") => ({
    paymentConceptId: concept,
    price: { amount, currency },
    validFrom,
    ...(validTo === undefined ? {} : { validTo }),
  });

  it("refuses an edit of a concept with another version with 409 stale-version, and moves the version on after each edit", async () => {
    const send = await session("ver-concept");

    const first = await send("PUT", `${CONCEPTS}/${COL}`, edit);
    const repeated = await send("PUT", `${CONCEPTS}/${COL}`, edit);
    const next = await send("PUT", `${CONCEPTS}/${COL}`, { ...edit, version: 1 });
    const missing = await send("PUT", `${CONCEPTS}/${COL}`, { name: "x", category: "TUITION", isRecurring: true, isTaxable: true, taxRateId: edit.taxRateId, isActive: true });

    expect(first.json).toMatchObject({ version: 1 });
    expect(verdict(repeated)).toEqual([409, "stale-version"]);
    expect(next.json).toMatchObject({ version: 2 });
    expect(verdict(missing)).toEqual([400, "validation-failed"]);
  });

  it("serves a stale version first in the stale-first scenario, once, and moves the record on", async () => {
    const send = await session("ver-first", "catalog-stale-version");

    const first = await send("PUT", `${CONCEPTS}/${COL}`, edit);
    const reread = (await send("GET", `${CONCEPTS}?size=200`)).json as { items: { id: string; version: number }[] };
    const retry = await send("PUT", `${CONCEPTS}/${COL}`, { ...edit, version: reread.items.find((row) => row.id === COL)?.version });

    expect(verdict(first)).toEqual([409, "stale-version"]);
    expect(reread.items.find((row) => row.id === COL)?.version).toBe(1);
    expect(retry.status).toBe(200);
  });

  it("lists the twenty seven seeded price lists by name, paged, and filters by year, scope and status on the server", async () => {
    const send = await session("pl-list");

    const first = await send("GET", `${LISTS}?size=25`);
    const second = await send("GET", `${LISTS}?size=25&page=1`);

    expect(total(first)).toBe(27);
    expect(names(first).slice(0, 3)).toEqual(["2025 — Institución", "2026 — Institución", "2026 — Primero"]);
    expect(names(second)).toHaveLength(2);
    expect(names(await send("GET", `${LISTS}?status=ARCHIVED`))).toEqual(["2025 — Institución"]);
    expect(names(await send("GET", `${LISTS}?scope=GRADE`))).toEqual(["2026 — Primero"]);
    expect(total(await send("GET", `${LISTS}?academicYearId=${YEAR}`))).toBe(2);
    expect(names(await send("GET", `${LISTS}?sort=name,desc&size=1`))).toEqual(["Lista genérica 24"]);
  });

  it("refuses a bad page, size, sort, id or filter with 400", async () => {
    const send = await session("pl-bad");

    for (const query of ["page=-1", "size=0", "size=201", "sort=version,asc", "sort=name,sideways", "academicYearId=x", "scope=ALL", "status=DELETED"]) {
      const reply = await send("GET", `${LISTS}?${query}`);
      expect([query, ...verdict(reply)]).toEqual([query, 400, "validation-failed"]);
    }
  });

  it("answers one list with all the validities of its prices, the amounts rounded as text, and 404 to one that is not there", async () => {
    const send = await session("pl-detail");

    const detail = await send("GET", `${LISTS}/${SEEDED_LIST}`);
    const missing = await send("GET", `${LISTS}/00000000-0000-4000-8000-000000000001`);

    expect(detail.json).toMatchObject({ id: SEEDED_LIST, name: "2026 — Institución", version: 0 });
    expect((detail.json as { items: ItemRow[] }).items.map((item) => [item.paymentConceptId, item.price.amount, item.validFrom, item.validTo])).toEqual([
      [COL, "1500.00", "2026-01-01", "2026-06-30"],
      [COL, "1650.00", "2026-07-01", null],
      ["00000000-0000-4000-8000-000000007002", "800.00", "2026-01-01", null],
      ["00000000-0000-4000-8000-000000007003", "35.50", "2026-01-01", null],
    ]);
    expect(verdict(missing)).toEqual([404, "resource-not-found"]);
  });

  it("creates a list with 201, lists it, and refuses a reference that does not fit the scope, a blank name and a body that breaks the contract", async () => {
    const send = await session("pl-create", "catalog-price-lists-empty");
    expect(total(await send("GET", LISTS))).toBe(0);

    const created = await send("POST", LISTS, NEW_LIST);
    const withoutReference = await send("POST", LISTS, { ...NEW_LIST, scope: "GRADE" });
    const withReference = await send("POST", LISTS, { ...NEW_LIST, scopeRefId: "00000000-0000-4000-8000-000000003001" });
    const blank = await send("POST", LISTS, { ...NEW_LIST, name: "  " });
    const broken = await send("POST", LISTS, { ...NEW_LIST, scope: "ALL" });

    expect(created.status).toBe(201);
    expect(created.json).toMatchObject({ name: "2027 — Institución", scope: "INSTITUTION", scopeRefId: null, status: "ACTIVE", version: 0 });
    expect([withoutReference, withReference, blank, broken].map(verdict)).toEqual(Array(4).fill([400, "validation-failed"]));
    expect(names(await send("GET", LISTS))).toEqual(["2027 — Institución"]);
  });

  it("archives and reactivates with the version, answers 409 stale-version to another one, and moves the version on", async () => {
    const send = await session("pl-update");
    const body = { version: 0, name: "2026 — Institución", status: "ARCHIVED" };

    const archived = await send("PUT", `${LISTS}/${SEEDED_LIST}`, body);
    const stale = await send("PUT", `${LISTS}/${SEEDED_LIST}`, { ...body, status: "ACTIVE" });
    const reactivated = await send("PUT", `${LISTS}/${SEEDED_LIST}`, { ...body, version: 1, status: "ACTIVE" });
    const missing = await send("PUT", `${LISTS}/00000000-0000-4000-8000-000000000001`, body);

    expect(archived.json).toMatchObject({ status: "ARCHIVED", version: 1 });
    expect(verdict(stale)).toEqual([409, "stale-version"]);
    expect(reactivated.json).toMatchObject({ status: "ACTIVE", version: 2 });
    expect(verdict(missing)).toEqual([404, "resource-not-found"]);
  });

  it("closes the open validity the day before a new open one, and keeps the history", async () => {
    const send = await session("pl-new-validity");

    const added = await send("POST", `${LISTS}/${SEEDED_LIST}/items`, price(COL, "1700.00", "2026-09-01"));
    const detail = (await send("GET", `${LISTS}/${SEEDED_LIST}`)).json as { items: ItemRow[] };
    const colegiatura = detail.items.filter((item) => item.paymentConceptId === COL).map((item) => [item.price.amount, item.validFrom, item.validTo]);

    expect(added.status).toBe(201);
    expect(added.json).toMatchObject({ price: { amount: "1700.00", currency: "HNL" }, validFrom: "2026-09-01", validTo: null });
    expect(colegiatura).toEqual([
      ["1500.00", "2026-01-01", "2026-06-30"],
      ["1650.00", "2026-07-01", "2026-08-31"],
      ["1700.00", "2026-09-01", null],
    ]);
  });

  it("answers the amount as the text that was sent, without rounding it, and refuses a third decimal", async () => {
    const send = await session("pl-round");
    const add = async (amount: string, from: string) => send("POST", `${LISTS}/${SEEDED_LIST}/items`, price(MUL, amount, from));

    expect(((await add("10.5", "2027-01-01")).json as ItemRow).price.amount).toBe("10.5");
    expect(((await add("9999999999.99", "2027-02-01")).json as ItemRow).price.amount).toBe("9999999999.99");
    const third = await add("10.005", "2027-03-01");
    expect(verdict(third)).toEqual([400, "validation-failed"]);
    expect((third.json as { errors: unknown[] }).errors).toEqual([{ field: "price.amount", reason: "pattern" }]);
  });

  it("refuses a validity day outside 2000-01-01 to 2100-12-31 naming the field, and accepts the two ends", async () => {
    const send = await session("pl-window");
    const add = async (from: string, to?: string) => send("POST", `${LISTS}/${SEEDED_LIST}/items`, price(MUL, "1.00", from, to));

    const early = await add("1999-12-31");
    const late = await add("2026-01-01", "2101-01-01");

    expect(verdict(early)).toEqual([400, "validation-failed"]);
    expect((early.json as { errors: unknown[] }).errors).toEqual([{ field: "validFrom", reason: "validity-day" }]);
    expect((late.json as { errors: unknown[] }).errors).toEqual([{ field: "validTo", reason: "validity-day" }]);
    expect((await add("2000-01-01", "2000-12-31")).status).toBe(201);
    expect((await add("2100-01-01", "2100-12-31")).status).toBe(201);
  });

  it("refuses an exact, partial and contained overlap with 409, and allows a contiguous validity and one before the open one", async () => {
    const send = await session("pl-overlap");
    const add = async (from: string, to?: string) => {
      const reply = await send("POST", `${LISTS}/${SEEDED_LIST}/items`, price(MUL, "10.00", from, to));
      return reply.status < 400 ? [reply.status] : verdict(reply);
    };

    expect(await add("2026-03-01", "2026-03-31")).toEqual([201]);
    expect(await add("2026-03-01", "2026-03-31")).toEqual([409, "price-validity-overlap"]);
    expect(await add("2026-03-15", "2026-04-15")).toEqual([409, "price-validity-overlap"]);
    expect(await add("2026-03-10", "2026-03-20")).toEqual([409, "price-validity-overlap"]);
    expect(await add("2026-04-01", "2026-04-30")).toEqual([201]);
    expect(await add("2026-02-01", "2026-02-28")).toEqual([201]);
    // An open one that starts inside a closed validity would leave no way to close the earlier one: refused too.
    expect(await add("2026-04-15")).toEqual([409, "price-validity-overlap"]);
  });

  it("refuses a bounded price inside the open validity of the same concept with 409, and a new open one that starts on or before it", async () => {
    const send = await session("pl-open");

    const inside = await send("POST", `${LISTS}/${SEEDED_LIST}/items`, price(COL, "1.00", "2026-08-01", "2026-08-31"));
    const same = await send("POST", `${LISTS}/${SEEDED_LIST}/items`, price(COL, "1.00", "2026-07-01"));

    expect([inside, same].map(verdict)).toEqual(Array(2).fill([409, "price-validity-overlap"]));
  });

  it("refuses a price on an archived list, for an inactive concept, for a concept that does not exist and for a list that does not exist", async () => {
    const send = await session("pl-refs");

    const archived = await send("POST", `${LISTS}/${ARCHIVED_LIST}/items`, price(COL, "1.00", "2030-01-01"));
    const inactive = await send("POST", `${LISTS}/${SEEDED_LIST}/items`, price(OTR, "1.00", "2026-01-01"));
    const foreign = await send("POST", `${LISTS}/${SEEDED_LIST}/items`, price("00000000-0000-4000-8000-000000000001", "1.00", "2026-01-01"));
    const missing = await send("POST", `${LISTS}/00000000-0000-4000-8000-000000000001/items`, price(COL, "1.00", "2026-01-01"));

    expect([archived, inactive, foreign, missing].map(verdict)).toEqual([
      [409, "price-list-archived"],
      [409, "inactive-reference"],
      [404, "resource-not-found"],
      [404, "resource-not-found"],
    ]);
  });

  it("refuses an amount of zero, an end before the start, an unknown currency and an amount that is a JSON number", async () => {
    const send = await session("pl-amount");
    const url = `${LISTS}/${SEEDED_LIST}/items`;

    const zero = await send("POST", url, price(MUL, "0.00", "2026-01-01"));
    const backwards = await send("POST", url, price(MUL, "1.00", "2026-02-01", "2026-01-01"));
    const currency = await send("POST", url, price(MUL, "1.00", "2026-01-01", undefined, "EUR"));
    const number = await send("POST", url, { ...price(MUL, "1.00", "2026-01-01"), price: { amount: 1, currency: "HNL" } });
    const decimals = await send("POST", url, price(MUL, "1.234", "2026-01-01"));

    expect([zero, backwards, currency, number, decimals].map(verdict)).toEqual(Array(5).fill([400, "validation-failed"]));
  });

  it("refuses every write of the price lists with 403 in the forbidden scenario, while the reads answer", async () => {
    const send = await session("pl-forbidden", "catalog-forbidden");

    const writes = [
      await send("POST", LISTS, NEW_LIST),
      await send("PUT", `${LISTS}/${SEEDED_LIST}`, { version: 0, name: "x", status: "ARCHIVED" }),
      await send("POST", `${LISTS}/${SEEDED_LIST}/items`, price(COL, "1.00", "2030-01-01")),
    ];

    expect(writes.map(verdict)).toEqual(Array(3).fill([403, "forbidden"]));
    expect(total(await send("GET", LISTS))).toBe(27);
  });

  it("fails the first three reads of the price lists with 500 and a trace id, then recovers", async () => {
    const send = await session("pl-server-error", "catalog-server-error");

    const failed = [await send("GET", LISTS), await send("GET", `${LISTS}/${SEEDED_LIST}`), await send("GET", LISTS)];
    const recovered = await send("GET", `${LISTS}/${SEEDED_LIST}`);

    expect(failed.map(verdict)).toEqual(Array(3).fill([500, "internal-error"]));
    expect(failed[0]?.json).toMatchObject({ traceId: "01J8Z4K2QF9P0X" });
    expect(recovered.status).toBe(200);
  });

  it("keeps one set of price lists for each scenario, and does not send them without an access token", async () => {
    const mine = await session("pl-mine", "catalog-price-lists-empty");
    const theirs = await session("pl-theirs");
    await mine("POST", LISTS, NEW_LIST);

    expect(total(await mine("GET", LISTS))).toBe(1);
    expect(total(await theirs("GET", LISTS))).toBe(27);
    expect(verdict(await call("GET", LISTS, { scenario: "pl-anonymous" }))).toEqual([401, "authentication-required"]);
    expect((await theirs("DELETE", `${LISTS}/${SEEDED_LIST}`)).status).toBe(404);
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
    expect(created.json).toMatchObject({ studentCode: "E-000061", firstName: "Marta", status: "ACTIVE", hasNationalId: true });
    expect(rows(found)).toHaveLength(1);
    expect(rows(found)[0]).toMatchObject({ hasNationalId: true });
    for (const reply of [created, found]) {
      expect(reply.text).not.toContain(DOCUMENT);
      expect(reply.text).not.toContain("0801201098765");
      expect(reply.json).not.toHaveProperty("nationalId");
    }
    const without = await send("POST", "", { ...NEW_STUDENT, studentCode: " MI-1 " });
    expect(without.json).toMatchObject({ studentCode: "MI-1", hasNationalId: false });
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
    expect(target).toMatchObject({ hasNationalId: true });

    const kept = await send("PUT", `/${id}`, edit);
    const replaced = await send("PUT", `/${id}`, { ...edit, nationalId: DOCUMENT });
    const removed = await send("PUT", `/${id}`, { ...edit, nationalId: "" });

    expect(kept.json).toMatchObject({ studentCode: "E-000042", firstName: "Luis Alberto", hasNationalId: true });
    expect(replaced.json).toMatchObject({ hasNationalId: true });
    expect(removed.json).toMatchObject({ hasNationalId: false });
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

describe("the tax rates and the rate of a concept [catalog-tax-rates]", { timeout: LIMIT }, () => {
  const RATES = "/api/v1/catalog/tax-rates";
  const CONCEPTS = "/api/v1/catalog/payment-concepts";
  const RATE_A = "00000000-0000-4000-8000-000000006001";
  const RATE_B = "00000000-0000-4000-8000-000000006002";
  const RATE_INACTIVE = "00000000-0000-4000-8000-000000006003";
  const COL = "00000000-0000-4000-8000-000000007001";
  const MAT = "00000000-0000-4000-8000-000000007002";
  const NEW_RATE = { code: "NUE-1", name: "Tasa nueva" };
  const concept = { version: 0, name: "x", category: "TUITION", isRecurring: true, isActive: true };

  /** Signs in on a scenario (after the preset, if any) and answers the call helper bound to its bearer token. */
  async function session(id: string, name?: string) {
    if (name !== undefined) await preset(id, name);
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: id });
    const headers = { Authorization: `Bearer ${(login.json as { accessToken: string }).accessToken}` };
    return (method: string, path: string, body?: unknown) => call(method, path, { headers, scenario: id, ...(body === undefined ? {} : { body }) });
  }

  interface RateRow {
    id: string;
    code: string;
    name: string;
    isActive: boolean;
    version: number;
  }
  interface PeriodRow {
    ratePercent: string;
    validFrom: string;
    validTo: string | null;
  }
  const codes = (reply: Reply) => (reply.json as { items: RateRow[] }).items.map((row) => row.code);
  const total = (reply: Reply) => (reply.json as { total: number }).total;
  const verdict = (reply: Reply) => [reply.status, typeOf(reply)];
  const periodsOf = async (send: Awaited<ReturnType<typeof session>>, id = RATE_A) =>
    ((await send("GET", `${RATES}/${id}`)).json as { periods: PeriodRow[] }).periods.map((period) => [period.ratePercent, period.validFrom, period.validTo]);

  it("answers 401 without an access token", async () => {
    expect([(await call("GET", RATES, { scenario: "tax-401" })).status, (await call("POST", `${RATES}/${RATE_A}/periods`, { scenario: "tax-401", body: {} })).status]).toEqual([401, 401]);
  });

  it("lists the twenty seven seeded rates by code, paged, and filters by state and sorts by name on the server", async () => {
    const send = await session("tax-list");

    const first = await send("GET", `${RATES}?size=25`);
    const second = await send("GET", `${RATES}?size=25&page=1`);

    expect(total(first)).toBe(27);
    expect(codes(first).slice(0, 4)).toEqual(["IMP-A", "IMP-B", "IMP-C", "TX-01"]);
    expect(codes(second)).toHaveLength(2);
    expect(codes(await send("GET", `${RATES}?active=false`))).toEqual(["IMP-C"]);
    expect(total(await send("GET", `${RATES}?active=true`))).toBe(26);
    expect(codes(await send("GET", `${RATES}?sort=name,desc&size=1`))).toEqual(["TX-24"]);
  });

  it("refuses a bad page, size, sort or filter with 400", async () => {
    const send = await session("tax-bad");

    for (const query of ["page=-1", "size=0", "size=201", "sort=version,asc", "sort=code,sideways", "active=maybe"]) {
      const reply = await send("GET", `${RATES}?${query}`);
      expect([query, ...verdict(reply)]).toEqual([query, 400, "validation-failed"]);
    }
  });

  it("answers one rate with all its validities, each percentage as text with four decimals, and 404 to one that is not there", async () => {
    const send = await session("tax-detail");

    const detail = await send("GET", `${RATES}/${RATE_A}`);
    const missing = await send("GET", `${RATES}/00000000-0000-4000-8000-000000000001`);

    expect(detail.json).toMatchObject({ id: RATE_A, code: "IMP-A", version: 0, isActive: true });
    expect(await periodsOf(send)).toEqual([
      ["1.2300", "2026-01-01", "2026-06-30"],
      ["3.2100", "2026-07-01", null],
    ]);
    expect(verdict(missing)).toEqual([404, "resource-not-found"]);
  });

  it("creates a rate with 201 and no percentage, lists it, and refuses a code that repeats whatever its capitals with 409", async () => {
    const send = await session("tax-create", "catalog-tax-rates-empty");
    expect(total(await send("GET", RATES))).toBe(0);

    const created = await send("POST", RATES, NEW_RATE);
    const repeated = await send("POST", RATES, { ...NEW_RATE, code: "nue-1" });
    const blank = await send("POST", RATES, { ...NEW_RATE, name: "  " });
    const pattern = await send("POST", RATES, { ...NEW_RATE, code: "NU E" });

    expect(created.status).toBe(201);
    expect(created.json).toMatchObject({ code: "NUE-1", name: "Tasa nueva", isActive: true, version: 0 });
    expect(verdict(repeated)).toEqual([409, "duplicate-tax-rate-code"]);
    expect(verdict(blank)).toEqual([400, "validation-failed"]);
    expect((pattern.json as { errors: unknown[] }).errors).toEqual([{ field: "code", reason: "pattern" }]);
    expect(codes(await send("GET", RATES))).toEqual(["NUE-1"]);
    expect(await periodsOf(send, (created.json as { id: string }).id)).toEqual([]);
  });

  it("edits the name and the state with the version, refuses a code in the body and another version with 409, and moves the version on", async () => {
    const send = await session("tax-edit");

    const edited = await send("PUT", `${RATES}/${RATE_A}`, { version: 0, name: "Otro nombre", isActive: false });
    const repeated = await send("PUT", `${RATES}/${RATE_A}`, { version: 0, name: "Otro nombre", isActive: false });
    const withCode = await send("PUT", `${RATES}/${RATE_A}`, { version: 1, name: "x", isActive: true, code: "NEW" });
    const missing = await send("PUT", `${RATES}/00000000-0000-4000-8000-000000000001`, { version: 0, name: "x", isActive: true });

    expect(edited.json).toMatchObject({ code: "IMP-A", name: "Otro nombre", isActive: false, version: 1 });
    expect(verdict(repeated)).toEqual([409, "stale-version"]);
    expect(verdict(withCode)).toEqual([400, "validation-failed"]);
    expect(verdict(missing)).toEqual([404, "resource-not-found"]);
  });

  it("serves a stale version first in the stale-first scenario, once, and moves the rate on", async () => {
    const send = await session("tax-stale", "catalog-stale-version");

    const first = await send("PUT", `${RATES}/${RATE_B}`, { version: 0, name: "x", isActive: true });
    const retry = await send("PUT", `${RATES}/${RATE_B}`, { version: 1, name: "x", isActive: true });

    expect(verdict(first)).toEqual([409, "stale-version"]);
    expect(retry.status).toBe(200);
  });

  it("closes the open validity the day before a new one that starts later, and answers 201 with the percentage at four decimals", async () => {
    const send = await session("tax-period");

    const added = await send("POST", `${RATES}/${RATE_A}/periods`, { ratePercent: "9.5", validFrom: "2026-09-01" });

    expect(added.status).toBe(201);
    expect(added.json).toMatchObject({ taxRateId: RATE_A, ratePercent: "9.5000", validFrom: "2026-09-01", validTo: null });
    expect(await periodsOf(send)).toEqual([
      ["1.2300", "2026-01-01", "2026-06-30"],
      ["3.2100", "2026-07-01", "2026-08-31"],
      ["9.5000", "2026-09-01", null],
    ]);
  });

  it("refuses an overlap with 409 and changes nothing, and takes a validity that only touches another", async () => {
    const send = await session("tax-overlap");

    const inside = await send("POST", `${RATES}/${RATE_A}/periods`, { ratePercent: "5", validFrom: "2026-02-01", validTo: "2026-03-31" });
    const partial = await send("POST", `${RATES}/${RATE_A}/periods`, { ratePercent: "5", validFrom: "2026-06-15", validTo: "2026-07-15" });
    const earlierOpen = await send("POST", `${RATES}/${RATE_A}/periods`, { ratePercent: "5", validFrom: "2025-01-01" });
    const contiguous = await send("POST", `${RATES}/${RATE_B}/periods`, { ratePercent: "5", validFrom: "2024-01-01", validTo: "2025-12-31" });

    expect([inside, partial, earlierOpen].map(verdict)).toEqual(Array(3).fill([409, "tax-rate-period-overlap"]));
    expect(contiguous.status).toBe(201);
    expect(await periodsOf(send)).toHaveLength(2);
  });

  it("refuses a percentage outside the pattern, zero, above 100 or sent as a number, and dates outside the window or reversed, with 400", async () => {
    const send = await session("tax-invalid");
    const at = (body: unknown) => send("POST", `${RATES}/${RATE_B}/periods`, body);

    for (const ratePercent of ["abc", "-1", "12.34567", "1234", ""]) {
      expect(((await at({ ratePercent, validFrom: "2030-01-01" })).json as { errors: unknown[] }).errors, ratePercent).toEqual([{ field: "ratePercent", reason: "pattern" }]);
    }
    expect(((await at({ ratePercent: 12.5, validFrom: "2030-01-01" })).json as { errors: unknown[] }).errors).toEqual([{ field: "ratePercent", reason: "invalid" }]);
    for (const ratePercent of ["0", "0.0000", "100.0001", "101"]) {
      expect(verdict(await at({ ratePercent, validFrom: "2030-01-01" })), ratePercent).toEqual([400, "validation-failed"]);
    }
    expect((await at({ ratePercent: "100", validFrom: "2030-01-01" })).status).toBe(201);
    expect(((await at({ ratePercent: "5", validFrom: "1999-12-31" })).json as { errors: unknown[] }).errors).toEqual([{ field: "validFrom", reason: "validity-day" }]);
    expect(((await at({ ratePercent: "5", validFrom: "2031-01-01", validTo: "2101-01-01" })).json as { errors: unknown[] }).errors).toEqual([
      { field: "validTo", reason: "validity-day" },
    ]);
    expect(verdict(await at({ ratePercent: "5", validFrom: "2031-01-02", validTo: "2031-01-01" }))).toEqual([400, "validation-failed"]);
  });

  it("refuses a validity of an inactive rate with 409 inactive-reference, and of one that is not there with 404", async () => {
    const send = await session("tax-inactive");

    const inactive = await send("POST", `${RATES}/${RATE_INACTIVE}/periods`, { ratePercent: "5", validFrom: "2027-01-01" });
    const missing = await send("POST", `${RATES}/00000000-0000-4000-8000-000000000001/periods`, { ratePercent: "5", validFrom: "2027-01-01" });

    expect(verdict(inactive)).toEqual([409, "inactive-reference"]);
    expect(verdict(missing)).toEqual([404, "resource-not-found"]);
  });

  it("refuses every write of a rate with 403 forbidden and changes nothing, while the reads keep answering", async () => {
    const send = await session("tax-forbidden", "catalog-forbidden");

    const replies = [
      await send("POST", RATES, NEW_RATE),
      await send("PUT", `${RATES}/${RATE_A}`, { version: 0, name: "x", isActive: true }),
      await send("POST", `${RATES}/${RATE_A}/periods`, { ratePercent: "5", validFrom: "2027-01-01" }),
    ];

    expect(replies.map(verdict)).toEqual(Array(3).fill([403, "forbidden"]));
    expect(total(await send("GET", RATES))).toBe(27);
  });

  it("fails the first three reads of the rates with 500 and a trace id, then recovers", async () => {
    const send = await session("tax-server-error", "catalog-server-error");

    const failed = [await send("GET", RATES), await send("GET", RATES), await send("GET", `${RATES}/${RATE_A}`)];
    const recovered = await send("GET", RATES);

    expect(failed.map(verdict)).toEqual(Array(3).fill([500, "internal-error"]));
    expect(failed[0]?.json).toMatchObject({ traceId: "01J8Z4K2QF9P0X" });
    expect(recovered.status).toBe(200);
  });

  it("keeps one set of rates for each scenario", async () => {
    const mine = await session("tax-mine", "catalog-tax-rates-empty");
    const theirs = await session("tax-theirs");
    await mine("POST", RATES, NEW_RATE);

    expect(total(await mine("GET", RATES))).toBe(1);
    expect(total(await theirs("GET", RATES))).toBe(27);
  });

  it("answers the rate of a concept with its code and name, and null for a concept that is not taxable", async () => {
    const send = await session("tax-concept-read");

    const items = ((await send("GET", `${CONCEPTS}?size=200`)).json as { items: { id: string; taxRateId: string | null; taxRateCode: string | null; taxRateName: string | null }[] }).items;

    expect(items.find((item) => item.id === COL)).toMatchObject({ taxRateId: RATE_A, taxRateCode: "IMP-A", taxRateName: "Impuesto de prueba A" });
    expect(items.find((item) => item.id === MAT)).toMatchObject({ taxRateId: null, taxRateCode: null, taxRateName: null });
  });

  it("requires a rate for a taxable concept and refuses one for a concept that is not taxable, with 400 and the field", async () => {
    const send = await session("tax-concept-rules");
    const edit = (body: object) => send("PUT", `${CONCEPTS}/${COL}`, { ...concept, ...body });

    const without = await edit({ isTaxable: true });
    const withRate = await edit({ isTaxable: false, taxRateId: RATE_A });
    const ok = await edit({ isTaxable: true, taxRateId: RATE_B });
    const notTaxable = await edit({ version: 1, isTaxable: false });

    expect((without.json as { errors: unknown[] }).errors).toEqual([{ field: "taxRateId", reason: "required" }]);
    expect((withRate.json as { errors: unknown[] }).errors).toEqual([{ field: "taxRateId", reason: "not-allowed" }]);
    expect(ok.json).toMatchObject({ taxRateId: RATE_B, taxRateCode: "IMP-B", version: 1 });
    expect(notTaxable.json).toMatchObject({ isTaxable: false, taxRateId: null, taxRateCode: null, taxRateName: null, version: 2 });
  });

  it("answers 404 to a rate that does not exist and 409 inactive-reference to an inactive one, but lets a concept keep the one it holds", async () => {
    const send = await session("tax-concept-ref");
    const edit = (body: object) => send("PUT", `${CONCEPTS}/${COL}`, { ...concept, isTaxable: true, ...body });

    const missing = await edit({ taxRateId: "00000000-0000-4000-8000-000000000001" });
    const inactive = await edit({ taxRateId: RATE_INACTIVE });
    await send("PUT", `${RATES}/${RATE_A}`, { version: 0, name: "Impuesto de prueba A", isActive: false });
    const kept = await edit({ taxRateId: RATE_A });

    expect(verdict(missing)).toEqual([404, "resource-not-found"]);
    expect(verdict(inactive)).toEqual([409, "inactive-reference"]);
    expect(kept.status).toBe(200);
    expect(kept.json).toMatchObject({ taxRateId: RATE_A });
  });
});

describe("the charge plans [charges-plans]", { timeout: LIMIT }, () => {
  const PLANS = "/api/v1/charges/plans";
  const YEAR = "00000000-0000-4000-8000-000000001030";
  const GRADE = "00000000-0000-4000-8000-000000003001";
  const SEEDED_DRAFT = "00000000-0000-4000-8000-000000009004";
  const MATRICULA = "00000000-0000-4000-8000-000000007002";
  const NEW_PLAN = { academicYearId: YEAR, name: "Primaria 2027", targetScope: "GRADE", targetRefId: GRADE, frequency: "MONTHLY", dueDayOfMonth: 5 };

  /** Signs in on a scenario (after the preset, if any) and answers the call helper bound to its bearer token. */
  async function session(id: string, name?: string) {
    if (name !== undefined) await preset(id, name);
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: id });
    const headers = { Authorization: `Bearer ${(login.json as { accessToken: string }).accessToken}` };
    return (method: string, path = "", body?: unknown) => call(method, `${PLANS}${path}`, { headers, scenario: id, ...(body === undefined ? {} : { body }) });
  }

  const verdict = (reply: Reply) => [reply.status, typeOf(reply)];
  const total = (reply: Reply) => (reply.json as { total: number }).total;

  it("answers 401 to every operation without an access token, and 404 to a path it does not simulate", async () => {
    const anonymous = [await call("GET", PLANS, { scenario: "pln-401" }), await call("POST", PLANS, { scenario: "pln-401", body: NEW_PLAN })];
    const send = await session("pln-404");

    expect(anonymous.map(verdict)).toEqual([
      [401, "authentication-required"],
      [401, "authentication-required"],
    ]);
    expect((await send("DELETE", "")).status).toBe(404);
    expect((await send("GET", "/x/y/z")).status).toBe(404);
  });

  it("lists the twenty eight seeded plans by name, creates one in draft, and keeps one set of plans for each scenario", async () => {
    const mine = await session("pln-mine", "charges-empty");
    const theirs = await session("pln-theirs");

    const created = await mine("POST", "", NEW_PLAN);

    expect(total(await theirs("GET"))).toBe(28);
    expect(created.status).toBe(201);
    expect(created.json).toMatchObject({ name: "Primaria 2027", status: "DRAFT", version: 0 });
    expect(total(await mine("GET"))).toBe(1);
  });

  it("adds an item to a draft, activates the plan, and then refuses to change its items with plan-not-editable", async () => {
    const send = await session("pln-flow");
    const item = { paymentConceptId: MATRICULA, periodsCount: 1, firstPeriodOn: "2027-02-01" };

    const added = await send("POST", `/${SEEDED_DRAFT}/items`, item);
    const detail = await send("GET", `/${SEEDED_DRAFT}`);
    const activated = await send("PUT", `/${SEEDED_DRAFT}`, { version: 1, name: "Segundo grado 2026", frequency: "MONTHLY", dueDayOfMonth: 5, status: "ACTIVE" });
    const late = await send("POST", `/${SEEDED_DRAFT}/items`, { ...item, periodsCount: 2 });

    expect(added.status).toBe(201);
    expect((detail.json as { items: unknown[] }).items).toHaveLength(2);
    expect(activated.json).toMatchObject({ status: "ACTIVE", version: 2 });
    expect(verdict(late)).toEqual([409, "plan-not-editable"]);
  });

  it("serves the three presets of failure: stale-first, forbidden and failing-first", async () => {
    const stale = await session("pln-stale", "charges-stale-version");
    const forbidden = await session("pln-forbidden", "charges-forbidden");
    const failing = await session("pln-failing", "charges-server-error");
    const body = { version: 1, name: "Segundo grado 2026", frequency: "MONTHLY", dueDayOfMonth: 5, status: "DRAFT" };

    const refused = await stale("PUT", `/${SEEDED_DRAFT}`, body);
    const denied = await forbidden("POST", "", NEW_PLAN);
    const failed = [await failing("GET"), await failing("GET"), await failing("GET")];
    const recovered = await failing("GET");

    expect(verdict(refused)).toEqual([409, "stale-version"]);
    expect(verdict(denied)).toEqual([403, "forbidden"]);
    expect(failed.map(verdict)).toEqual(Array(3).fill([500, "internal-error"]));
    expect(recovered.status).toBe(200);
  });

  it("removes an item with a 204 and no body", async () => {
    const send = await session("pln-remove");

    const removed = await send("DELETE", `/${SEEDED_DRAFT}/items/00000000-0000-4000-8000-000000009504`);

    expect(removed.status).toBe(204);
    expect(removed.text).toBe("");
  });
});

describe("the accrual engine [charges-accrual]", { timeout: LIMIT }, () => {
  const ACTIVE_PLAN = "00000000-0000-4000-8000-000000009001";
  const PLAN = `/api/v1/charges/plans/${ACTIVE_PLAN}`;
  const FRESH = { periodStart: "2026-05-01" };

  async function session(id: string, name?: string) {
    if (name !== undefined) await preset(id, name);
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: id });
    const bearer = { Authorization: `Bearer ${(login.json as { accessToken: string }).accessToken}` };
    return (method: string, path: string, options: { body?: unknown; key?: string } = {}) =>
      call(method, path, {
        headers: { ...bearer, ...(options.key === undefined ? {} : { "Idempotency-Key": options.key }) },
        scenario: id,
        ...(options.body === undefined ? {} : { body: options.body }),
      });
  }

  it("answers 401 to every operation without an access token", async () => {
    const answers = [
      await call("GET", `${PLAN}/accrual-periods`, { scenario: "acc-401" }),
      await call("POST", `${PLAN}/accrual-preview`, { scenario: "acc-401", body: FRESH }),
      await call("GET", "/api/v1/charges/accrual-runs", { scenario: "acc-401" }),
    ];

    expect(answers.map((answer) => answer.status)).toEqual([401, 401, 401]);
  });

  it("carries the Idempotency-Key of the request to the engine: without it 400, with it 202 and the same run for the same key", async () => {
    const send = await session("acc-key");

    const missing = await send("POST", `${PLAN}/accrual-runs`, { body: FRESH });
    const first = await send("POST", `${PLAN}/accrual-runs`, { body: FRESH, key: "k-1" });
    const again = await send("POST", `${PLAN}/accrual-runs`, { body: FRESH, key: "k-1" });

    expect([missing.status, typeOf(missing)]).toEqual([400, "idempotency-key-missing"]);
    expect(first.status).toBe(202);
    expect(again.json).toMatchObject({ id: (first.json as { id: string }).id });
  });

  it("serves a run that progresses over its reads", async () => {
    const send = await session("acc-run");
    const queued = await send("POST", `${PLAN}/accrual-runs`, { body: FRESH, key: "k-1" });
    const id = (queued.json as { id: string }).id;

    const statuses = [await send("GET", `/api/v1/charges/accrual-runs/${id}`), await send("GET", `/api/v1/charges/accrual-runs/${id}`), await send("GET", `/api/v1/charges/accrual-runs/${id}`)];

    expect(statuses.map((reply) => (reply.json as { run: { status: string } }).run.status)).toEqual(["QUEUED", "RUNNING", "COMPLETED_WITH_ERRORS"]);
  });

  it.each([
    ["charges-accrual-forbidden", "forbidden", 403],
    ["charges-accrual-in-progress", "accrual-run-in-progress", 409],
  ] as const)("serves the %s preset: the request is refused with %s", async (name, code, status) => {
    const send = await session(`acc-${name}`, name);

    const refused = await send("POST", `${PLAN}/accrual-runs`, { body: FRESH, key: "k-1" });

    expect([refused.status, typeOf(refused)]).toEqual([status, code]);
  });

  it("serves the empty and blocked presets", async () => {
    const empty = await session("acc-empty", "charges-accrual-empty");
    const blocked = await session("acc-blocked", "charges-accrual-blocked");

    const runs = await empty("GET", "/api/v1/charges/accrual-runs");
    const preview = await blocked("POST", `${PLAN}/accrual-preview`, { body: FRESH });

    expect(runs.json).toMatchObject({ total: 0 });
    expect(preview.json).toMatchObject({ blockers: ["posting-accounts-incomplete"] });
  });
});

describe("the chart of accounts and the map of posting accounts [ledger-chart-of-accounts]", { timeout: LIMIT }, () => {
  const ACCOUNTS = "/api/v1/ledger/accounts";
  const MAP = "/api/v1/charges/posting-accounts";
  const RECEIVABLE = "00000000-0000-4000-8000-000000008001";
  const CASH = "00000000-0000-4000-8000-000000008002";

  async function session(id: string, name?: string) {
    if (name !== undefined) await preset(id, name);
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: id });
    const headers = { Authorization: `Bearer ${(login.json as { accessToken: string }).accessToken}` };
    return (method: string, path: string, body?: unknown) => call(method, path, { headers, scenario: id, ...(body === undefined ? {} : { body }) });
  }

  const items = (reply: Reply) => (reply.json as { items: Record<string, unknown>[] }).items;

  it("answers 401 to every operation without an access token, and 404 to a path it does not simulate", async () => {
    const anonymous = [
      await call("GET", ACCOUNTS, { scenario: "led-401" }),
      await call("POST", ACCOUNTS, { scenario: "led-401", body: {} }),
      await call("GET", MAP, { scenario: "led-401" }),
      await call("PUT", MAP, { scenario: "led-401", body: {} }),
    ];
    const send = await session("led-404");

    expect(anonymous.map((reply) => [reply.status, typeOf(reply)])).toEqual(Array.from({ length: 4 }, () => [401, "authentication-required"]));
    expect((await send("DELETE", `${ACCOUNTS}/${CASH}`)).status).toBe(404);
    expect((await send("GET", "/api/v1/ledger/other")).status).toBe(404);
  });

  it("serves the chart and the partial map of the default scenario, and keeps one set of both for each scenario", async () => {
    const mine = await session("led-mine", "ledger-empty");
    const theirs = await session("led-theirs");

    const created = await mine("POST", ACCOUNTS, { code: "ACT-100", name: "Cuenta de prueba", type: "ASSET", holdsStudentBalances: false });

    expect(created.status).toBe(201);
    expect((await mine("GET", ACCOUNTS)).json).toMatchObject({ total: 1 });
    expect((await theirs("GET", ACCOUNTS)).json).toMatchObject({ total: 28 });
    expect(items(await theirs("GET", MAP)).map((row) => row["purpose"])).toEqual(["RECEIVABLE", "TAX_PAYABLE"]);
  });

  it("saves the map through the charges path, which the ledger model owns, and refuses a type that does not fit", async () => {
    const send = await session("led-map");

    const saved = await send("PUT", MAP, { entries: [{ purpose: "REVENUE", ledgerAccountId: "00000000-0000-4000-8000-000000008004" }] });
    const refused = await send("PUT", MAP, { entries: [{ purpose: "RECEIVABLE", ledgerAccountId: CASH, version: 0 }] });

    expect(saved.status).toBe(200);
    expect(items(saved)).toHaveLength(3);
    expect([refused.status, typeOf(refused)]).toEqual([409, "posting-account-invalid"]);
  });

  it.each([
    ["ledger-map-empty", 0],
    ["ledger-map-complete", 5],
  ] as const)("serves the %s preset with %i rows in the map", async (name, rows) => {
    const send = await session(`led-${name}`, name);

    expect(items(await send("GET", MAP))).toHaveLength(rows);
  });

  it.each([
    ["ledger-forbidden", "POST", 403, "forbidden"],
    ["ledger-stale-version", "PUT", 409, "stale-version"],
  ] as const)("serves the %s preset: the write is refused with %s", async (name, method, status, code) => {
    const send = await session(`led-${name}`, name);

    const refused = method === "POST"
      ? await send("POST", ACCOUNTS, { code: "ACT-100", name: "Cuenta de prueba", type: "ASSET", holdsStudentBalances: false })
      : await send("PUT", `${ACCOUNTS}/${RECEIVABLE}`, { version: 0, name: "Otro nombre", active: true });

    expect([refused.status, typeOf(refused)]).toEqual([status, code]);
  });

  it("fails the first reads of the accounts or of the map, each in its own preset, and then recovers", async () => {
    const accounts = await session("led-err-accounts", "ledger-server-error");
    const mapOnly = await session("led-err-map", "ledger-map-server-error");

    const statuses = async (send: Awaited<ReturnType<typeof session>>, path: string) => {
      const seen: number[] = [];
      for (let read = 0; read < 4; read += 1) seen.push((await send("GET", path)).status);
      return seen;
    };

    expect(await statuses(accounts, ACCOUNTS)).toEqual([500, 500, 500, 200]);
    expect((await accounts("GET", MAP)).status).toBe(200);
    expect(await statuses(mapOnly, MAP)).toEqual([500, 500, 500, 200]);
    expect((await mapOnly("GET", ACCOUNTS)).status).toBe(200);
  });
});

describe("the statement of account [ledger-account-statement]", { timeout: LIMIT }, () => {
  const ANA = "00000000-0000-4000-8000-000000005001";
  const NO_MOVEMENTS = "00000000-0000-4000-8000-000000005003";
  const FOREIGN = "00000000-0000-4000-8000-000000009404";
  const statementOf = (id: string) => `/api/v1/charges/students/${id}/statement`;

  async function session(id: string, name?: string) {
    if (name !== undefined) await preset(id, name);
    const login = await call("POST", "/api/v1/auth/sessions", { body: credentials, scenario: id });
    const headers = { Authorization: `Bearer ${(login.json as { accessToken: string }).accessToken}` };
    return (method: string, path: string) => call(method, path, { headers, scenario: id });
  }

  it("answers 401 without an access token", async () => {
    expect((await call("GET", statementOf(ANA), { scenario: "stm-401" })).status).toBe(401);
  });

  it("serves the statement of the seeded student with its sections and checks it against the contract", async () => {
    const send = await session("stm-ok");

    const reply = await send("GET", `${statementOf(ANA)}?size=25`);

    const body = reply.json as { studentCode: string; sections: { currency: string; totalLines: number }[] };
    expect(reply.status).toBe(200);
    expect(body.studentCode).toBe("E-000001");
    expect(body.sections.map((section) => [section.currency, section.totalLines])).toEqual([["HNL", 14]]);
    const violations = await call("GET", "/__control/violations", { scenario: "stm-ok" });
    expect(violations.json).toEqual([]);
  });

  it("answers 404 for a student of another institution and 400 for a period that ends before it starts", async () => {
    const send = await session("stm-404");

    expect([(await send("GET", statementOf(FOREIGN))).status, typeOf(await send("GET", statementOf(FOREIGN)))]).toEqual([404, "resource-not-found"]);
    expect((await send("GET", `${statementOf(ANA)}?from=2026-02-01&to=2026-01-01`)).status).toBe(400);
  });

  it.each([
    ["statement-forbidden", ANA, 403],
    ["statement-empty", NO_MOVEMENTS, 200],
  ] as const)("serves the %s preset", async (name, student, status) => {
    const send = await session(`stm-${name}`, name);

    const reply = await send("GET", statementOf(student));

    expect(reply.status).toBe(status);
    if (status === 200) expect((reply.json as { sections: unknown[] }).sections).toEqual([]);
  });

  it("fails the first reads of the failing preset and then recovers", async () => {
    const send = await session("stm-err", "statement-server-error");

    const seen: number[] = [];
    for (let read = 0; read < 4; read += 1) seen.push((await send("GET", statementOf(ANA))).status);

    expect(seen).toEqual([500, 500, 500, 200]);
  });
});

