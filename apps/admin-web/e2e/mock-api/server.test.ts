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
