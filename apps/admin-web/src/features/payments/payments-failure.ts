import { ApiFailure, type ApiFailureResult } from "../../shared/api/api-failure.ts";
import type { AccessFailure } from "../../shared/forms/access-failure.ts";
import { classifyOrganizationFailure } from "../../shared/resource-screen/resource-failure.ts";

/** The field of each amount the server names nested (`amount.amount`), so the error lands under the one control the form has. */
const AMOUNT_FIELDS: ReadonlySet<string> = new Set(["amount.amount", "amount.currency"]);

function fieldOf(error: { readonly field: string; readonly reason: string }): { readonly field: string; readonly reason: string } {
  if (AMOUNT_FIELDS.has(error.field)) return { field: "amount", reason: error.reason === "required" || error.reason === "not-null" ? "required" : "amount-format" };
  if (error.field === "reference" && error.reason === "size") return { field: "reference", reason: "reference-length" };
  if (error.reason === "pattern" && (error.field === "reference" || error.field === "bankName" || error.field === "notes" || error.field === "reason")) {
    return { field: error.field, reason: "cash-reason-control" };
  }
  if (error.field === "reason" && error.reason === "size") return { field: "reason", reason: "cash-reason-length" };
  return error;
}

/**
 * Sorts a failed write of the payments. A reference already used for that bank belongs to the reference field, so it is shown under it; every
 * other business code has its message (the cash session that is not open, the accounts that are incomplete, the self-approval, a payment that is
 * no longer declared), and the field errors of a 400 go to their fields.
 */
export function classifyPaymentFailure(result: ApiFailureResult): AccessFailure {
  if (result.kind === "problem" && result.problem.code === "duplicate-transfer-reference") {
    return { kind: "fields", errors: [{ field: "reference", reason: "duplicate-transfer-reference" }] };
  }
  const general = classifyOrganizationFailure(result);
  return general.kind === "fields" ? { kind: "fields", errors: general.errors.map(fieldOf) } : general;
}

/** What a rejected write holds, as the failure a form shows and whether the server may have done the write (so the same intent is sent again). */
export function paymentFailureOf(error: unknown): { readonly failure: AccessFailure; readonly outcomeUnknown: boolean } {
  if (!(error instanceof ApiFailure)) return { failure: { kind: "unexpected" }, outcomeUnknown: true };
  const { result } = error;
  // The network failed, the answer made no sense, or the server failed: it may or may not have done the write. Any other answer is a definite no.
  const outcomeUnknown = result.kind === "network" || result.kind === "unexpected-response" || result.problem.status >= 500;
  return { failure: classifyPaymentFailure(result), outcomeUnknown };
}
