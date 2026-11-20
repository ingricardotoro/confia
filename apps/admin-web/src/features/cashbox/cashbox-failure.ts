import { ApiFailure, type ApiFailureResult } from "../../shared/api/api-failure.ts";
import type { AccessFailure } from "../../shared/forms/access-failure.ts";
import { classifyOrganizationFailure } from "../../shared/resource-screen/resource-failure.ts";

/** The field of each amount the server names nested (`openingFloat.amount`), so the error lands under the one control the form has. */
const AMOUNT_FIELDS: Readonly<Record<string, string>> = {
  "openingFloat.amount": "openingFloat",
  "openingFloat.currency": "openingFloat",
  "declaredAmount.amount": "declaredAmount",
  "declaredAmount.currency": "declaredAmount",
  "amount.amount": "amount",
  "amount.currency": "amount",
};

/** The free texts the server bounds: too short or too long is `size`, a control character is `pattern`. */
const TEXT_FIELDS: ReadonlySet<string> = new Set(["differenceReason", "notes", "note"]);

function fieldOf(error: { readonly field: string; readonly reason: string }): { readonly field: string; readonly reason: string } {
  const amount = AMOUNT_FIELDS[error.field];
  if (amount !== undefined) return { field: amount, reason: error.reason === "required" || error.reason === "not-null" ? "required" : "amount-format" };
  if (TEXT_FIELDS.has(error.field)) {
    if (error.reason === "size") return { field: error.field, reason: error.field === "note" ? "too-long" : "cash-reason-length" };
    if (error.reason === "pattern") return { field: error.field, reason: "cash-reason-control" };
  }
  return error;
}

/**
 * Sorts a failed write of the cashbox. A count that differs from the expected cash with no reason is the one refusal that belongs to a
 * field (the reason of the difference), so it is shown under it; every other business code has its message, and the field errors of a
 * 400 go to their fields.
 */
export function classifyCashboxFailure(result: ApiFailureResult): AccessFailure {
  if (result.kind === "problem" && result.problem.code === "cash-difference-reason-required") {
    return { kind: "fields", errors: [{ field: "differenceReason", reason: "difference-reason-required" }] };
  }
  const general = classifyOrganizationFailure(result);
  return general.kind === "fields" ? { kind: "fields", errors: general.errors.map(fieldOf) } : general;
}

/** What a rejected write holds, as the failure a form shows and whether the server may have done the write (so the same intent is sent again). */
export function cashFailureOf(error: unknown): { readonly failure: AccessFailure; readonly outcomeUnknown: boolean } {
  if (!(error instanceof ApiFailure)) return { failure: { kind: "unexpected" }, outcomeUnknown: true };
  const { result } = error;
  // The network failed, the answer made no sense, or the server failed: it may or may not have done the write. Any other answer is a definite no.
  const outcomeUnknown = result.kind === "network" || result.kind === "unexpected-response" || result.problem.status >= 500;
  return { failure: classifyCashboxFailure(result), outcomeUnknown };
}
