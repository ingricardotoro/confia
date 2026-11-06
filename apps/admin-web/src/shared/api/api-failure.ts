import type { ApiResult } from "./http.ts";

/** Every result that is not `ok`. */
export type ApiFailureResult = Exclude<ApiResult<unknown>, { kind: "ok" }>;

/**
 * What a query function throws for a result that is not `ok`, so the query client can tell a failure
 * that may pass from one that will not (design.md D9). The message names the kind and the status and
 * nothing of the body.
 */
export class ApiFailure extends Error {
  readonly result: ApiFailureResult;

  constructor(result: ApiFailureResult) {
    const status = result.kind === "problem" ? result.problem.status : "status" in result ? result.status : undefined;
    super(status === undefined ? `API failure: ${result.kind}` : `API failure: ${result.kind} ${String(status)}`);
    this.name = "ApiFailure";
    this.result = result;
  }
}

/**
 * A read is worth repeating after a network failure or a 5xx. A 4xx is the server's answer, a 2xx that
 * broke the schema would answer the same, and an error that is not an `ApiFailure` is a defect, which
 * repeating does not fix.
 */
export function isRetryableFailure(error: unknown): boolean {
  if (!(error instanceof ApiFailure)) return false;
  const { result } = error;
  return result.kind === "network" || (result.kind === "problem" && result.problem.status >= 500);
}
