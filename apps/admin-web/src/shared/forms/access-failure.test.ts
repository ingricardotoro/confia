import { describe, expect, it } from "vitest";
import type { ApiFailureResult } from "../api/api-failure.ts";
import type { ApiProblem, ProblemCode } from "../api/problem.ts";
import { classifyAccessFailure } from "./access-failure.ts";

// admin-web-staff-sign-in, task 2.2 (E5). The five forms of failure, from the cases the screens meet.
function problem(status: number, code: ProblemCode | "unknown", rest: Partial<ApiProblem> = {}): ApiFailureResult {
  return { kind: "problem", problem: { status, code, errors: [], ...rest } };
}

describe("classifyAccessFailure", () => {
  it("a 400 with errors is a failure of fields and carries every error", () => {
    const errors = [
      { field: "password", reason: "max" },
      { field: "identifier", reason: "not-blank" },
    ];

    expect(classifyAccessFailure(problem(400, "validation-failed", { errors }))).toEqual({ kind: "fields", errors });
  });

  it("a 400 without errors is a message that the data could not be processed", () => {
    expect(classifyAccessFailure(problem(400, "validation-failed", { traceId: "T-400" }))).toEqual({
      kind: "message",
      key: "problem.notProcessed",
      traceId: "T-400",
    });
  });

  it("a 401 authentication-failed is the credentials message, with no trace id when there is none", () => {
    expect(classifyAccessFailure(problem(401, "authentication-failed"))).toEqual({
      kind: "message",
      key: "problem.authenticationFailed",
    });
  });

  it("a 401 second-factor-invalid is the code message, which a screen may replace with its own", () => {
    expect(classifyAccessFailure(problem(401, "second-factor-invalid"))).toEqual({
      kind: "message",
      key: "problem.secondFactorInvalid",
    });
  });

  it.each(["token-expired", "token-invalid"] as const)("a 401 %s ends the restricted token", (code) => {
    expect(classifyAccessFailure(problem(401, code))).toEqual({ kind: "restricted-token-ended" });
  });

  it("a 429 carries the seconds the problem already bounded", () => {
    expect(classifyAccessFailure(problem(429, "too-many-requests", { retryAfterSeconds: 15 }))).toEqual({
      kind: "rate-limited",
      retryAfterSeconds: 15,
    });
  });

  it("a 429 whose problem has no seconds waits the default minute", () => {
    expect(classifyAccessFailure(problem(429, "too-many-requests"))).toEqual({ kind: "rate-limited", retryAfterSeconds: 60 });
  });

  it("a 503 is the busy message and keeps the trace id", () => {
    expect(classifyAccessFailure(problem(503, "capacity-exceeded", { traceId: "T-503" }))).toEqual({
      kind: "message",
      key: "problem.capacityExceeded",
      traceId: "T-503",
    });
  });

  it("a 500 is unexpected and keeps the trace id", () => {
    expect(classifyAccessFailure(problem(500, "internal-error", { traceId: "T-500" }))).toEqual({
      kind: "unexpected",
      traceId: "T-500",
    });
  });

  it("an unknown problem and a code that no form handles are unexpected", () => {
    expect(classifyAccessFailure(problem(418, "unknown"))).toEqual({ kind: "unexpected" });
    expect(classifyAccessFailure(problem(403, "forbidden", { traceId: "T-403" }))).toEqual({
      kind: "unexpected",
      traceId: "T-403",
    });
  });

  it("a network failure is the connection message", () => {
    expect(classifyAccessFailure({ kind: "network" })).toEqual({ kind: "message", key: "problem.network" });
  });

  it("AW64: a 2xx outside the schema is unexpected and has no trace id", () => {
    expect(classifyAccessFailure({ kind: "unexpected-response", status: 200 })).toEqual({ kind: "unexpected" });
  });
});
