import {
  AuthenticatedSessionResponse,
  CurrentSessionResponse,
  EnrollmentResponse,
  LoginResponse,
  ProblemDetail,
} from "@confia/contracts/admin";
import { describe, expect, it } from "vitest";
import {
  ContractViolation,
  authenticatedSession,
  currentSession,
  enrollment,
  noContent,
  problem,
  secondFactorEnrollmentRequired,
  secondFactorRequired,
} from "./contract-responses.ts";

// admin-web-foundations-and-session, task 5.1 (design.md D15). The responses the tests and, later, the
// simulated API answer with are built in one place and checked against the generated contract before
// they leave it, so a contract that moves breaks a test instead of leaving it green on stale data.
const NOW = new Date("2026-11-06T12:00:00.000Z");

describe("the successful responses", () => {
  it("builds an authenticated session that meets the contract, with a token made at run time", () => {
    const first = authenticatedSession({ now: NOW });
    const second = authenticatedSession({ now: NOW });

    expect(first.status).toBe(200);
    expect(AuthenticatedSessionResponse.safeParse(first.body).success).toBe(true);
    expect(LoginResponse.safeParse(first.body).success).toBe(true);
    expect(first.body).toMatchObject({ status: "authenticated" });
    expect(first.body).not.toEqual(second.body);
  });

  it("derives the expiry times from the given clock, never from the real one", () => {
    const body = authenticatedSession({ now: NOW }).body as { accessTokenExpiresAt: string };

    expect(Date.parse(body.accessTokenExpiresAt)).toBeGreaterThan(NOW.getTime());
    expect(Date.parse(body.accessTokenExpiresAt)).toBeLessThan(NOW.getTime() + 24 * 60 * 60 * 1000);
  });

  it.each([
    ["second-factor-required", secondFactorRequired],
    ["second-factor-enrollment-required", secondFactorEnrollmentRequired],
  ] as const)("builds a %s response that meets the contract", (status, build) => {
    const response = build({ now: NOW });

    expect(response.status).toBe(200);
    expect(LoginResponse.safeParse(response.body).success).toBe(true);
    expect(response.body).toMatchObject({ status });
  });

  it("builds a current session that meets the contract", () => {
    const response = currentSession({ now: NOW });

    expect(response.status).toBe(200);
    expect(CurrentSessionResponse.safeParse(response.body).success).toBe(true);
  });

  it("builds an enrollment with a base32 secret and ten recovery codes, all made at run time", () => {
    const response = enrollment();

    expect(response.status).toBe(201);
    expect(EnrollmentResponse.safeParse(response.body).success).toBe(true);
    expect((response.body as { recoveryCodes: string[] }).recoveryCodes).toHaveLength(10);
    expect(enrollment().body).not.toEqual(response.body);
  });

  it("builds an empty 204", () => {
    expect(noContent()).toEqual({ status: 204, body: undefined, contentType: undefined });
  });
});

describe("a response that breaks the contract", () => {
  it("is refused before it is returned, naming the response and the field but never the value", () => {
    const sentinel = `not-a-date-${crypto.randomUUID()}`;
    let thrown: unknown;

    try {
      authenticatedSession({ now: NOW, override: { accessTokenExpiresAt: sentinel } });
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(ContractViolation);
    const message = (thrown as Error).message;
    expect(message).toContain("AuthenticatedSessionResponse");
    expect(message).toContain("accessTokenExpiresAt");
    expect(message).not.toContain(sentinel);
  });

  it("refuses an enrollment whose secret is not base32 of thirty two characters", () => {
    expect(() => enrollment({ override: { secret: "too-short" } })).toThrow(ContractViolation);
  });
});

describe("a problem response", () => {
  it("carries the type of its code, a relative instance as the backend emits it, and the problem media type", () => {
    const response = problem("authentication-failed", 401, { traceId: "trace-1" });

    expect(response.status).toBe(401);
    expect(response.contentType).toBe("application/problem+json");
    expect(response.body).toMatchObject({
      type: "https://confia.hn/problems/authentication-failed",
      status: 401,
      traceId: "trace-1",
    });
    expect((response.body as { instance: string }).instance.startsWith("/")).toBe(true);
  });

  it("meets the problem contract once the relative instance is set aside", () => {
    const body = problem("validation-failed", 400, { errors: [{ field: "identifier", reason: "required" }] })
      .body as Record<string, unknown>;
    const rest = Object.fromEntries(Object.entries(body).filter(([name]) => name !== "instance"));

    expect(ProblemDetail.omit({ instance: true }).safeParse(rest).success).toBe(true);
  });

  it("is refused when its status is not an error status", () => {
    expect(() => problem("forbidden", 200)).toThrow(ContractViolation);
  });
});
