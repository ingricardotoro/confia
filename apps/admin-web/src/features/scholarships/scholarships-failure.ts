import { ApiFailure, type ApiFailureResult } from "../../shared/api/api-failure.ts";
import type { AccessFailure } from "../../shared/forms/access-failure.ts";
import { classifyOrganizationFailure } from "../../shared/resource-screen/resource-failure.ts";

/**
 * Sorts a failed write of the awards. The person who requested an award cannot decide it (`self-approval-forbidden`), and that has its own text here
 * (the shared one speaks of payments); every other business code has its message in the shared classifier, and the field errors of a 400 go to their
 * fields, with the reason of a decision worded as a bounded text.
 */
export function classifyAwardFailure(result: ApiFailureResult): AccessFailure {
  if (result.kind === "problem" && result.problem.code === "self-approval-forbidden") {
    const { traceId } = result.problem;
    return { kind: "message", key: "scholarships.errors.selfApproval", ...(traceId === undefined ? {} : { traceId }) };
  }
  const general = classifyOrganizationFailure(result);
  if (general.kind !== "fields" || result.kind !== "problem") return general;
  // The shared classifier words the size of a reason for the withdrawal of an enrollment (3 to 500); the reason of a decision is 20 to 500 and has its own
  // words. It keeps the order of the errors, so each one is read beside the error the server sent.
  const sent = result.problem.errors;
  return {
    kind: "fields",
    errors: general.errors.map((error, index) => {
      const original = sent[index];
      if (original?.field !== "reason") return error;
      if (original.reason === "size") return { field: "reason", reason: "cash-reason-length" };
      if (original.reason === "pattern") return { field: "reason", reason: "cash-reason-control" };
      return error;
    }),
  };
}

/** What a rejected write holds, as the failure a form shows and whether the server may have done the write (so the same intent is sent again). */
export function awardFailureOf(error: unknown): { readonly failure: AccessFailure; readonly outcomeUnknown: boolean } {
  if (!(error instanceof ApiFailure)) return { failure: { kind: "unexpected" }, outcomeUnknown: true };
  const { result } = error;
  // The network failed, the answer made no sense, or the server failed: it may or may not have done the write. Any other answer is a definite no.
  const outcomeUnknown = result.kind === "network" || result.kind === "unexpected-response" || result.problem.status >= 500;
  return { failure: classifyAwardFailure(result), outcomeUnknown };
}

/**
 * Sorts a failed write of the agreement assignments the way the awards are sorted: the person who requested an assignment cannot decide it
 * (`self-approval-forbidden`), with the text of the assignments, and every other code goes through the classifier of the awards (the reason of a
 * decision worded as a bounded text, and the business codes of the shared classifier).
 */
export function classifyAssignmentFailure(result: ApiFailureResult): AccessFailure {
  if (result.kind === "problem" && result.problem.code === "self-approval-forbidden") {
    const { traceId } = result.problem;
    return { kind: "message", key: "scholarships.errors.assignmentSelfApproval", ...(traceId === undefined ? {} : { traceId }) };
  }
  return classifyAwardFailure(result);
}

/** What a rejected write of an assignment holds, as the failure a form shows and whether the server may have done the write (so the same intent is sent again). */
export function assignmentFailureOf(error: unknown): { readonly failure: AccessFailure; readonly outcomeUnknown: boolean } {
  const { failure, outcomeUnknown } = awardFailureOf(error);
  return error instanceof ApiFailure ? { failure: classifyAssignmentFailure(error.result), outcomeUnknown } : { failure, outcomeUnknown };
}

/**
 * Sorts a failed decision of a campaign discount: whoever created the discount or proposed the change cannot decide it (`self-approval-forbidden`), with the
 * text of the discounts, and every other code goes through the classifier of the awards (the reason of a decision worded as a bounded text, and the
 * business codes of the shared classifier: a decision already taken, another version, a missing permission).
 */
export function classifyDiscountFailure(result: ApiFailureResult): AccessFailure {
  if (result.kind === "problem" && result.problem.code === "self-approval-forbidden") {
    const { traceId } = result.problem;
    return { kind: "message", key: "scholarships.errors.discountSelfApproval", ...(traceId === undefined ? {} : { traceId }) };
  }
  return classifyAwardFailure(result);
}

/** What a rejected decision of a discount holds, as the failure a dialog shows and whether the server may have done it (so the same intent is sent again). */
export function discountFailureOf(error: unknown): { readonly failure: AccessFailure; readonly outcomeUnknown: boolean } {
  const { failure, outcomeUnknown } = awardFailureOf(error);
  return error instanceof ApiFailure ? { failure: classifyDiscountFailure(error.result), outcomeUnknown } : { failure, outcomeUnknown };
}
