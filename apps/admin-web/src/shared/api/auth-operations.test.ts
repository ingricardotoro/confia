import { EnrollmentResponse } from "@confia/contracts/admin";
import { describe, expect, it } from "vitest";
import {
  authenticatedSession,
  currentSession,
  enrollment,
  noContent,
  problem,
  secondFactorEnrollmentRequired,
  secondFactorRequired,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import {
  completeSecondFactor,
  endCurrentSession,
  enrollSecondFactor,
  getCurrentSession,
  logIn,
  refreshSession,
} from "./auth-operations.ts";

// admin-web-foundations-and-session, task 5.1 (AW33 to AW39, AW41, AW42, I310, design.md
// D6). The six operations run through the real transport against a test server that records what the
// app sent. Tokens and the CSRF value are made here at run time and never written down (rule 13).
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const REFRESH = "/api/v1/auth/sessions/refresh";
const CURRENT = "/api/v1/auth/sessions/current";
const ENROLLMENTS = "/api/v1/auth/mfa/enrollments";
const VERIFICATIONS = "/api/v1/auth/mfa/verifications";

const token = (): string => `tok-${crypto.randomUUID()}`;

function only(): ReturnType<typeof requests>[number] {
  const [sent, ...rest] = requests();
  expect(rest).toEqual([]);
  if (sent === undefined) throw new Error("No request was recorded.");
  return sent;
}

function bodyKeys(body: string): string[] {
  return Object.keys(JSON.parse(body) as Record<string, unknown>).sort();
}

describe("logIn", () => {
  it("AW33: posts exactly the identifier and the password as JSON, with no token, CSRF value or idempotency key", async () => {
    route("post", SESSIONS, authenticatedSession());

    await logIn({ identifier: "staff-1", password: `pw-${crypto.randomUUID()}` });

    const sent = only();
    expect(sent.method).toBe("POST");
    expect(sent.path).toBe(SESSIONS);
    expect(sent.headers.get("Content-Type")).toBe("application/json");
    expect(bodyKeys(sent.body)).toEqual(["identifier", "password"]);
    expect(sent.headers.has("Authorization")).toBe(false);
    expect(sent.headers.has("X-CSRF-Token")).toBe(false);
    expect(sent.headers.has("Idempotency-Key")).toBe(false);
  });

  it("sends the values as typed, and nothing else the caller might have added", async () => {
    route("post", SESSIONS, authenticatedSession());
    const credentials = { identifier: "staff-1", password: `pw-${crypto.randomUUID()}`, extra: "x" };

    await logIn(credentials);

    expect(JSON.parse(only().body)).toEqual({ identifier: credentials.identifier, password: credentials.password });
  });

  it("AW41: a 200 that does not meet LoginResponse is an unexpected response without any token", async () => {
    const sentinel = token();
    route("post", SESSIONS, { status: 200, body: { status: "authenticated", accessToken: sentinel }, contentType: "application/json" });

    const result = await logIn({ identifier: "staff-1", password: "x" });

    expect(result).toEqual({ kind: "unexpected-response", status: 200 });
    expect(JSON.stringify(result)).not.toContain(sentinel);
  });

  it("AW42: tells the three valid responses apart by status, each with its own typed fields", async () => {
    const outcomes: string[] = [];
    for (const reply of [authenticatedSession(), secondFactorRequired(), secondFactorEnrollmentRequired()]) {
      route("post", SESSIONS, reply);
      const result = await logIn({ identifier: "staff-1", password: "x" });
      if (result.kind !== "ok") throw new Error(`Expected ok, got ${result.kind}.`);
      switch (result.data.status) {
        case "authenticated":
          expect(result.data.accessToken).toEqual(expect.any(String));
          break;
        case "second-factor-required":
        case "second-factor-enrollment-required":
          expect(result.data.secondFactorToken).toEqual(expect.any(String));
          expect("accessToken" in result.data).toBe(false);
          break;
      }
      outcomes.push(result.data.status);
    }

    expect(outcomes).toEqual(["authenticated", "second-factor-required", "second-factor-enrollment-required"]);
  });

  it("returns the problem code of a refused sign in", async () => {
    route("post", SESSIONS, problem("authentication-failed", 401));

    expect(await logIn({ identifier: "staff-1", password: "x" })).toMatchObject({
      kind: "problem",
      problem: { status: 401, code: "authentication-failed" },
    });
  });
});

describe("refreshSession", () => {
  it("AW34: posts with the CSRF value, no Authorization and no body, carrying the same origin cookies", async () => {
    route("post", REFRESH, authenticatedSession());

    const result = await refreshSession("csrf-A");

    const sent = only();
    expect(sent.method).toBe("POST");
    expect(sent.path).toBe(REFRESH);
    expect(sent.headers.get("X-CSRF-Token")).toBe("csrf-A");
    expect(sent.headers.has("Authorization")).toBe(false);
    expect(sent.headers.has("Content-Type")).toBe(false);
    expect(sent.body).toBe("");
    expect(sent.credentials).toBe("same-origin");
    expect(result).toMatchObject({ kind: "ok", status: 200, data: { status: "authenticated" } });
  });

  it("AW65: the operations pass on the server date and the arrival of the answer, and add no header to the request", async () => {
    route("post", REFRESH, { ...authenticatedSession(), headers: { Date: "Tue, 10 Nov 2026 10:00:00 GMT" } });

    const result = await refreshSession("csrf-A");

    expect(result).toMatchObject({ kind: "ok", serverDate: Date.UTC(2026, 10, 10, 10, 0, 0) });
    expect(result.kind === "ok" && Number.isFinite(result.receivedAt)).toBe(true);
    expect(only().headers.get("X-CSRF-Token")).toBe("csrf-A");
    expect(only().headers.has("Authorization")).toBe(false);
  });

  it("AW41: a refresh whose body does not meet the schema is an unexpected response", async () => {
    route("post", REFRESH, { status: 200, body: { status: "authenticated" }, contentType: "application/json" });

    expect(await refreshSession("csrf-A")).toEqual({ kind: "unexpected-response", status: 200 });
  });
});

describe("endCurrentSession", () => {
  it("AW35: deletes the current session with the CSRF value and no Authorization", async () => {
    route("delete", CURRENT, noContent());

    const result = await endCurrentSession("csrf-A");

    const sent = only();
    expect(sent.method).toBe("DELETE");
    expect(sent.path).toBe(CURRENT);
    expect(sent.headers.get("X-CSRF-Token")).toBe("csrf-A");
    expect(sent.headers.has("Authorization")).toBe(false);
    expect(result).toMatchObject({ kind: "ok", status: 204, data: undefined });
  });

  it("AW36: with no CSRF value it sends no X-CSRF-Token header and invents none", async () => {
    route("delete", CURRENT, noContent());

    await endCurrentSession(undefined);

    expect(only().headers.has("X-CSRF-Token")).toBe(false);
  });

  it("reports the status it got, so the caller can tell a 204 from any other 2xx", async () => {
    route("delete", CURRENT, { status: 200, body: undefined, contentType: undefined });

    expect(await endCurrentSession("csrf-A")).toMatchObject({ kind: "ok", status: 200, data: undefined });
  });
});

describe("getCurrentSession", () => {
  it("AW37: reads with the access token as Bearer and no CSRF header", async () => {
    const access = token();
    route("get", CURRENT, currentSession());

    const result = await getCurrentSession(access);

    const sent = only();
    expect(sent.method).toBe("GET");
    expect(sent.path).toBe(CURRENT);
    expect(sent.headers.get("Authorization")).toBe(`Bearer ${access}`);
    expect(sent.headers.has("X-CSRF-Token")).toBe(false);
    expect(sent.body).toBe("");
    expect(result.kind).toBe("ok");
  });

  it("AW41: a session that does not meet the schema is an unexpected response", async () => {
    route("get", CURRENT, { status: 200, body: { accountId: "not-a-uuid" }, contentType: "application/json" });

    expect(await getCurrentSession(token())).toEqual({ kind: "unexpected-response", status: 200 });
  });
});

describe("the second factor", () => {
  it("AW38: enrolls with the restricted token as Bearer and no body", async () => {
    const restricted = token();
    route("post", ENROLLMENTS, enrollment());

    const result = await enrollSecondFactor(restricted);

    const sent = only();
    expect(sent.method).toBe("POST");
    expect(sent.path).toBe(ENROLLMENTS);
    expect(sent.headers.get("Authorization")).toBe(`Bearer ${restricted}`);
    expect(sent.headers.has("X-CSRF-Token")).toBe(false);
    expect(sent.body).toBe("");
    expect(result).toMatchObject({ kind: "ok", status: 201 });
  });

  it("AW38: verifies with a code, the body holding exactly code", async () => {
    const restricted = token();
    route("post", VERIFICATIONS, authenticatedSession());

    const result = await completeSecondFactor(restricted, { kind: "code", value: "123456" });

    const sent = only();
    expect(sent.method).toBe("POST");
    expect(sent.path).toBe(VERIFICATIONS);
    expect(sent.headers.get("Authorization")).toBe(`Bearer ${restricted}`);
    expect(sent.headers.get("Content-Type")).toBe("application/json");
    expect(JSON.parse(sent.body)).toEqual({ code: "123456" });
    expect(result).toMatchObject({ kind: "ok", data: { status: "authenticated" } });
  });

  it("AW38: verifies with a recovery code, the body holding exactly recoveryCode", async () => {
    route("post", VERIFICATIONS, authenticatedSession());

    await completeSecondFactor(token(), { kind: "recovery-code", value: "abcde-12345" });

    expect(JSON.parse(only().body)).toEqual({ recoveryCode: "abcde-12345" });
  });

  it("returns the problem code of a code the server refused", async () => {
    route("post", VERIFICATIONS, problem("second-factor-invalid", 401));

    expect(await completeSecondFactor(token(), { kind: "code", value: "000000" })).toMatchObject({
      kind: "problem",
      problem: { status: 401, code: "second-factor-invalid" },
    });
  });

  it("I310: the enrollment response holds exactly the secret and the recovery codes, nothing to draw a QR from", async () => {
    expect(Object.keys(EnrollmentResponse.shape).sort()).toEqual(["recoveryCodes", "secret"]);
    route("post", ENROLLMENTS, {
      status: 201,
      body: {
        ...(enrollment().body as object),
        otpauthUri: "otpauth://totp/CONFIA:staff?secret=SENTINEL",
        qrCode: "data:image/png;base64,SENTINEL",
      },
      contentType: "application/json",
    });

    const result = await enrollSecondFactor(token());

    if (result.kind !== "ok") throw new Error(`Expected ok, got ${result.kind}.`);
    expect(Object.keys(result.data).sort()).toEqual(["recoveryCodes", "secret"]);
    expect(JSON.stringify(result.data)).not.toMatch(/otpauth|data:image/);
  });
});

describe("what no operation sends", () => {
  const operations: readonly (readonly [string, () => Promise<unknown>, string, "get" | "post" | "delete"])[] = [
    ["logIn", () => logIn({ identifier: "staff-1", password: "x" }), SESSIONS, "post"],
    ["refreshSession", () => refreshSession("csrf-A"), REFRESH, "post"],
    ["endCurrentSession", () => endCurrentSession("csrf-A"), CURRENT, "delete"],
    ["getCurrentSession", () => getCurrentSession(token()), CURRENT, "get"],
    ["enrollSecondFactor", () => enrollSecondFactor(token()), ENROLLMENTS, "post"],
    ["completeSecondFactor", () => completeSecondFactor(token(), { kind: "code", value: "1" }), VERIFICATIONS, "post"],
  ];

  it.each(operations)("AW39: %s carries no Idempotency-Key", async (_name, run, path, method) => {
    route(method, path, noContent());

    await run();

    expect(only().headers.has("Idempotency-Key")).toBe(false);
  });
});
