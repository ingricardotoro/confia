import { describe, expect, it } from "vitest";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { classifyAwardFailure, awardFailureOf } from "./scholarships-failure.ts";

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
