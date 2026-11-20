import { describe, expect, it } from "vitest";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { cashFailureOf, classifyCashboxFailure } from "./cashbox-failure.ts";

const problem = (code: string, status: number, errors: { field: string; reason: string }[] = []) =>
  ({ kind: "problem", problem: { status, code, errors } }) as never;

describe("classifyCashboxFailure", () => {
  it("puts the missing reason of a difference under its own field", () => {
    expect(classifyCashboxFailure(problem("cash-difference-reason-required", 400))).toEqual({
      kind: "fields",
      errors: [{ field: "differenceReason", reason: "difference-reason-required" }],
    });
  });

  it.each([
    ["cash-session-already-open", "problem.cashSessionAlreadyOpen", 409],
    ["cash-session-not-open", "problem.cashSessionNotOpen", 409],
    ["cash-session-not-awaiting-approval", "problem.cashSessionNotAwaitingApproval", 409],
    ["cash-expected-negative", "problem.cashExpectedNegative", 409],
    ["cash-approval-same-actor", "problem.cashApprovalSameActor", 403],
    ["idempotency-payload-mismatch", "problem.idempotencyPayloadMismatch", 422],
  ] as const)("tells %s with its own message", (code, key, status) => {
    expect(classifyCashboxFailure(problem(code, status))).toMatchObject({ kind: "message", key });
  });

  it("lands the field errors of a 400 under the one control each form has", () => {
    const failure = classifyCashboxFailure(
      problem("validation-failed", 400, [
        { field: "declaredAmount.amount", reason: "pattern" },
        { field: "differenceReason", reason: "size" },
        { field: "notes", reason: "pattern" },
        { field: "note", reason: "size" },
      ]),
    );

    expect(failure).toEqual({
      kind: "fields",
      errors: [
        { field: "declaredAmount", reason: "amount-format" },
        { field: "differenceReason", reason: "cash-reason-length" },
        { field: "notes", reason: "cash-reason-control" },
        { field: "note", reason: "too-long" },
      ],
    });
  });
});

describe("cashFailureOf", () => {
  it("says the outcome is unknown when the network failed, the answer made no sense or the server failed", () => {
    expect(cashFailureOf(new ApiFailure({ kind: "network" })).outcomeUnknown).toBe(true);
    expect(cashFailureOf(new ApiFailure({ kind: "unexpected-response", status: 201 })).outcomeUnknown).toBe(true);
    expect(cashFailureOf(new ApiFailure(problem("internal-error", 500))).outcomeUnknown).toBe(true);
    expect(cashFailureOf(new Error("boom")).outcomeUnknown).toBe(true);
  });

  it("says the outcome is known when the server answered no, so the next try is another request", () => {
    expect(cashFailureOf(new ApiFailure(problem("cash-expected-negative", 409))).outcomeUnknown).toBe(false);
    expect(cashFailureOf(new ApiFailure(problem("validation-failed", 400))).outcomeUnknown).toBe(false);
  });
});
