import { describe, expect, it } from "vitest";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { assignmentFailureOf, awardFailureOf, classifyAssignmentFailure, classifyAwardFailure, classifyDiscountFailure, discountFailureOf } from "./scholarships-failure.ts";

// How a refusal of the awards is sorted: the person who requested an award cannot decide it (with the text of the awards, not the one of the payments),
// the reason of a decision is worded as a bounded text, and a refusal that leaves the outcome unknown keeps the intent so it is sent again.
const problem = (code: string, status: number, errors: { field: string; reason: string }[] = []) =>
  ({ kind: "problem", problem: { status, code, errors, traceId: "t1" } }) as never;

describe("classifyAwardFailure", () => {
  it("gives the self approval its own text and keeps the trace", () => {
    expect(classifyAwardFailure(problem("self-approval-forbidden", 403))).toEqual({ kind: "message", key: "scholarships.errors.selfApproval", traceId: "t1" });
  });

  it("leaves every other business code to the shared classifier", () => {
    expect(classifyAwardFailure(problem("scholarship-award-overlap", 409))).toEqual({ kind: "message", key: "problem.scholarshipAwardOverlap", traceId: "t1" });
    expect(classifyAwardFailure(problem("invalid-award-state", 409))).toEqual({ kind: "message", key: "problem.invalidAwardState", traceId: "t1" });
  });

  it("words the size and the pattern of the reason as a bounded text, and leaves the other fields as they are", () => {
    const failure = classifyAwardFailure(
      problem("validation-failed", 400, [
        { field: "reason", reason: "size" },
        { field: "validTo", reason: "invalid" },
      ]),
    );
    expect(failure).toEqual({
      kind: "fields",
      errors: [
        { field: "reason", reason: "cash-reason-length" },
        { field: "validTo", reason: "invalid" },
      ],
    });
    expect(classifyAwardFailure(problem("validation-failed", 400, [{ field: "reason", reason: "pattern" }]))).toEqual({
      kind: "fields",
      errors: [{ field: "reason", reason: "cash-reason-control" }],
    });
  });
});

describe("awardFailureOf", () => {
  it("is sure of a definite no and unsure of a network failure, an answer that makes no sense and a server failure", () => {
    expect(awardFailureOf(new ApiFailure(problem("invalid-award-state", 409))).outcomeUnknown).toBe(false);
    expect(awardFailureOf(new ApiFailure({ kind: "network" })).outcomeUnknown).toBe(true);
    expect(awardFailureOf(new ApiFailure({ kind: "unexpected-response", status: 200 })).outcomeUnknown).toBe(true);
    expect(awardFailureOf(new ApiFailure(problem("internal-error", 500))).outcomeUnknown).toBe(true);
    expect(awardFailureOf(new Error("boom"))).toEqual({ failure: { kind: "unexpected" }, outcomeUnknown: true });
  });
});

describe("classifyAssignmentFailure", () => {
  it("gives the self approval the text of the assignments, not the one of the awards, and keeps the trace", () => {
    expect(classifyAssignmentFailure(problem("self-approval-forbidden", 403))).toEqual({ kind: "message", key: "scholarships.errors.assignmentSelfApproval", traceId: "t1" });
    expect(classifyAwardFailure(problem("self-approval-forbidden", 403))).toEqual({ kind: "message", key: "scholarships.errors.selfApproval", traceId: "t1" });
  });

  it("tells the codes of the assignments with their own text", () => {
    expect(classifyAssignmentFailure(problem("discount-assignment-overlap", 409))).toEqual({ kind: "message", key: "problem.discountAssignmentOverlap", traceId: "t1" });
    expect(classifyAssignmentFailure(problem("invalid-discount-assignment-state", 409))).toEqual({
      kind: "message",
      key: "problem.invalidDiscountAssignmentState",
      traceId: "t1",
    });
  });

  it("words the size of the reason as a bounded text, as the awards do", () => {
    expect(classifyAssignmentFailure(problem("validation-failed", 400, [{ field: "reason", reason: "size" }]))).toEqual({
      kind: "fields",
      errors: [{ field: "reason", reason: "cash-reason-length" }],
    });
  });
});

describe("assignmentFailureOf", () => {
  it("is sure of a definite no and unsure of a network failure and a server failure, with the text of the assignments", () => {
    const refused = assignmentFailureOf(new ApiFailure(problem("self-approval-forbidden", 403)));
    expect(refused.outcomeUnknown).toBe(false);
    expect(refused.failure).toEqual({ kind: "message", key: "scholarships.errors.assignmentSelfApproval", traceId: "t1" });
    expect(assignmentFailureOf(new ApiFailure({ kind: "network" })).outcomeUnknown).toBe(true);
    expect(assignmentFailureOf(new ApiFailure(problem("internal-error", 500))).outcomeUnknown).toBe(true);
    expect(assignmentFailureOf(new Error("boom"))).toEqual({ failure: { kind: "unexpected" }, outcomeUnknown: true });
  });
});

describe("classifyDiscountFailure", () => {
  it("gives the self approval of a discount its own text, with the trace", () => {
    expect(classifyDiscountFailure(problem("self-approval-forbidden", 403))).toEqual({ kind: "message", key: "scholarships.errors.discountSelfApproval", traceId: "t1" });
  });

  it("leaves the new business codes of the discounts to the shared classifier", () => {
    expect(classifyDiscountFailure(problem("discount-pending-approval", 409))).toEqual({ kind: "message", key: "problem.discountPendingApproval", traceId: "t1" });
    expect(classifyDiscountFailure(problem("invalid-discount-change-state", 409))).toEqual({ kind: "message", key: "problem.invalidDiscountChangeState", traceId: "t1" });
    expect(classifyAssignmentFailure(problem("discount-terms-changed", 409))).toEqual({ kind: "message", key: "problem.discountTermsChanged", traceId: "t1" });
  });

  it("keeps the intent of a decision whose outcome is unknown and renews it after a definite refusal", () => {
    expect(discountFailureOf(new ApiFailure({ kind: "network" })).outcomeUnknown).toBe(true);
    expect(discountFailureOf(new ApiFailure(problem("self-approval-forbidden", 403))).outcomeUnknown).toBe(false);
    expect(discountFailureOf(new ApiFailure(problem("internal-error", 500))).outcomeUnknown).toBe(true);
    expect(discountFailureOf(new Error("boom"))).toEqual({ failure: { kind: "unexpected" }, outcomeUnknown: true });
  });
});
