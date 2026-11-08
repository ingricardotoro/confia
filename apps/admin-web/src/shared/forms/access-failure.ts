import type { AdminKey } from "../../i18n/resources.ts";
import type { ApiFailureResult } from "../api/api-failure.ts";
import { PROBLEM_KEYS } from "../api/problem-keys.ts";

/** What a form needs to know about a failed request, with no text in it (design.md E5). */
export type AccessFailure =
  /** A 400 with `errors`. */
  | { readonly kind: "fields"; readonly errors: readonly { readonly field: string; readonly reason: string }[] }
  /** A 429. The seconds are already bounded by the problem reader. */
  | { readonly kind: "rate-limited"; readonly retryAfterSeconds: number }
  /** A 401 of credentials, a 503, a 400 without `errors` and the network. */
  | { readonly kind: "message"; readonly key: AdminKey; readonly traceId?: string }
  /** A 500, an unknown problem and a 2xx outside the schema (AW64). */
  | { readonly kind: "unexpected"; readonly traceId?: string }
  /** A 401 `token-expired` or `token-invalid`. */
  | { readonly kind: "restricted-token-ended" };

const DEFAULT_RETRY_AFTER_SECONDS = 60;

function withTrace(traceId: string | undefined): { traceId?: string } {
  return traceId === undefined ? {} : { traceId };
}

/**
 * Sorts a result that is not `ok` into one of five forms. It is the general case: a screen decides what is its
 * own first (a second factor code that is wrong has a text for each mode) and delegates the rest here.
 */
export function classifyAccessFailure(result: ApiFailureResult): AccessFailure {
  if (result.kind === "network") return { kind: "message", key: "problem.network" };
  if (result.kind === "unexpected-response") return { kind: "unexpected" };

  const { code, errors, retryAfterSeconds, traceId } = result.problem;
  switch (code) {
    case "validation-failed":
      return errors.length > 0
        ? { kind: "fields", errors }
        : { kind: "message", key: PROBLEM_KEYS[code], ...withTrace(traceId) };
    case "too-many-requests":
      return { kind: "rate-limited", retryAfterSeconds: retryAfterSeconds ?? DEFAULT_RETRY_AFTER_SECONDS };
    case "token-expired":
    case "token-invalid":
      return { kind: "restricted-token-ended" };
    case "authentication-failed":
    case "second-factor-invalid":
    case "capacity-exceeded":
      return { kind: "message", key: PROBLEM_KEYS[code], ...withTrace(traceId) };
    default:
      return { kind: "unexpected", ...withTrace(traceId) };
  }
}
