import type { AdminKey } from "../../i18n/resources.ts";
import { ApiFailure, type ApiFailureResult } from "../../shared/api/api-failure.ts";
import { PROBLEM_KEYS } from "../../shared/api/problem-keys.ts";
import { classifyAccessFailure, type AccessFailure } from "../../shared/forms/access-failure.ts";

/**
 * The codes of the academic structure, the two every write can meet, and the two of the students registry, the five
 * of the enrollments and the one of the guardians (this classifier serves the four screens), that have a text of their own.
 */
const MESSAGE_CODES: ReadonlySet<string> = new Set([
  "academic-year-overlap",
  "academic-year-already-active",
  "academic-year-closed",
  "duplicate-name",
  "inactive-reference",
  "forbidden",
  "resource-not-found",
  "duplicate-student-code",
  "duplicate-national-id",
  "student-not-active",
  "section-full",
  "duplicate-active-enrollment",
  "invalid-enrollment-state",
  "transfer-year-mismatch",
  "guardian-not-active",
]);

/** The text of each reason the server gives for a split of the responsibility that does not hold. */
const SPLIT_KEYS: Readonly<Record<string, AdminKey>> = {
  "sum-not-100": "problem.splitSumNot100",
  "multiple-primary-payer": "problem.splitMultiplePrimaryPayer",
  "multiple-billing-recipient": "problem.splitMultipleBillingRecipient",
  "duplicate-guardian": "problem.splitDuplicateGuardian",
};

/**
 * The year's rule that the end is after the start is a method of the request, so the server names it `endAfterStart`.
 * A national id the pattern refuses gets its own reason, so the message says what a valid document looks like.
 */
function fieldOf(error: { readonly field: string; readonly reason: string }): { readonly field: string; readonly reason: string } {
  if (error.field === "endAfterStart") return { field: "endDate", reason: "end-after-start" };
  if (error.field === "nationalId" && error.reason === "pattern") return { field: "nationalId", reason: "national-id-format" };
  // The reason of a withdrawal or a transfer is 3 to 500 characters: `size` says it is too short as well as too long.
  if ((error.field === "withdrawalReason" || error.field === "reason") && error.reason === "size") return { field: error.field, reason: "reason-length" };
  // The contact data of a guardian: the server names a format it refuses `pattern`, and each has its own message.
  if (error.reason === "pattern" && (error.field === "rtn" || error.field === "email" || error.field === "phone")) {
    return { field: error.field, reason: `${error.field}-format` };
  }
  return error;
}

/**
 * Sorts a failed write of the academic structure: each business code to its own message, the field errors of a
 * 400 to their fields, and the rest as the general case does. The decision about the 403 stays with the server:
 * the screen only tells the person plainly that the answer was no.
 */
export function classifyOrganizationFailure(result: ApiFailureResult): AccessFailure {
  if (result.kind === "problem" && MESSAGE_CODES.has(result.problem.code)) {
    const { code, traceId } = result.problem;
    return { kind: "message", key: PROBLEM_KEYS[code], ...(traceId === undefined ? {} : { traceId }) };
  }
  if (result.kind === "problem" && result.problem.code === "invalid-responsibility-split") {
    const { errors, traceId } = result.problem;
    const reason = errors[0]?.reason ?? "";
    const key = Object.hasOwn(SPLIT_KEYS, reason) ? SPLIT_KEYS[reason] : undefined;
    return { kind: "message", key: key ?? PROBLEM_KEYS["invalid-responsibility-split"], ...(traceId === undefined ? {} : { traceId }) };
  }
  const general = classifyAccessFailure(result);
  return general.kind === "fields" ? { kind: "fields", errors: general.errors.map(fieldOf) } : general;
}

/** What a rejected mutation or query holds, as a failure a form or a screen can show. */
export function failureOf(error: unknown): AccessFailure {
  return error instanceof ApiFailure ? classifyOrganizationFailure(error.result) : { kind: "unexpected" };
}

export interface LoadFailure {
  readonly key: AdminKey;
  readonly traceId?: string;
}

/** The message of a failed read, with the trace id when the server sent one. */
export function loadFailureOf(error: unknown): LoadFailure {
  if (!(error instanceof ApiFailure)) return { key: "problem.unexpected" };
  const { result } = error;
  if (result.kind === "network") return { key: "problem.network" };
  if (result.kind !== "problem") return { key: "problem.unexpected" };
  const { code, traceId } = result.problem;
  return { key: PROBLEM_KEYS[code], ...(traceId === undefined ? {} : { traceId }) };
}

/** The message of a failure the person has to be told about outside a form, as a catalog key and a trace id. */
export function failureMessageOf(failure: AccessFailure): LoadFailure {
  switch (failure.kind) {
    case "message":
      return { key: failure.key, ...(failure.traceId === undefined ? {} : { traceId: failure.traceId }) };
    case "rate-limited":
      return { key: "problem.rateLimited" };
    case "fields":
      return { key: "problem.notProcessed" };
    case "unexpected":
      return { key: "problem.unexpected", ...(failure.traceId === undefined ? {} : { traceId: failure.traceId }) };
    case "restricted-token-ended":
      return { key: "problem.unexpected" };
  }
}
