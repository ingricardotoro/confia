import { reportUnknownProblem, stripQueryAndFragment, type UnknownProblemFinding } from "./report-unknown-problem.ts";

/** The twenty codes the backend publishes today (docs/ui-ux/04, §9, its dated note, and the academic structure cut). */
export const KNOWN_PROBLEM_CODES = [
  "authentication-failed",
  "second-factor-invalid",
  "session-expired",
  "csrf-invalid",
  "mfa-already-enrolled",
  "validation-failed",
  "unsupported-media-type",
  "too-many-requests",
  "capacity-exceeded",
  "internal-error",
  "authentication-required",
  "token-invalid",
  "token-expired",
  "forbidden",
  "resource-not-found",
  "academic-year-overlap",
  "academic-year-already-active",
  "academic-year-closed",
  "duplicate-name",
  "inactive-reference",
] as const;

export type ProblemCode = (typeof KNOWN_PROBLEM_CODES)[number];

/**
 * An error response reduced to what the interface may use. `title`, `detail` and `instance` are not in
 * the type, so they cannot be shown or logged by mistake (AW49, AW55; docs/ui-ux/04, §9).
 */
export interface ApiProblem {
  readonly status: number;
  readonly code: ProblemCode | "unknown";
  readonly traceId?: string;
  readonly errors: readonly { readonly field: string; readonly reason: string }[];
  readonly retryAfterSeconds?: number;
}

const PROBLEM_TYPE_PREFIX = "https://confia.hn/problems/";
const MAX_FIELD_ERRORS = 50;
const DEFAULT_RETRY_AFTER_SECONDS = 60;
const MAX_RETRY_AFTER_SECONDS = 3600;
const WHOLE_SECONDS = /^[1-9]\d*$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** The exact type `https://confia.hn/problems/<code>` with a known code; anything else is unknown (AW47). */
function codeOf(type: unknown): ProblemCode | undefined {
  if (typeof type !== "string" || !type.startsWith(PROBLEM_TYPE_PREFIX)) return undefined;
  const code = type.slice(PROBLEM_TYPE_PREFIX.length);
  return KNOWN_PROBLEM_CODES.find((known) => known === code);
}

function fieldErrorsOf(value: unknown): ApiProblem["errors"] {
  if (!Array.isArray(value)) return [];
  const errors: { field: string; reason: string }[] = [];
  for (const entry of value as unknown[]) {
    if (isRecord(entry) && typeof entry.field === "string" && typeof entry.reason === "string") {
      errors.push({ field: entry.field, reason: entry.reason });
    }
  }
  return errors.slice(0, MAX_FIELD_ERRORS);
}

/** Whole seconds only; absent, zero, text or an HTTP date give 60, and a long wait is cut to an hour (AW52, AW53). */
function retryAfterSecondsOf(header: string | null): number {
  if (header === null || !WHOLE_SECONDS.test(header)) return DEFAULT_RETRY_AFTER_SECONDS;
  return Math.min(Number(header), MAX_RETRY_AFTER_SECONDS);
}

async function bodyOf(response: Response): Promise<Record<string, unknown>> {
  try {
    const parsed: unknown = JSON.parse(await response.text());
    return isRecord(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

/**
 * Turns an error response into an `ApiProblem` and never throws. It does not use `ProblemDetail.safeParse`:
 * `instance` arrives relative and `zod.url()` would reject every real error (design.md D7), so it reads
 * `type`, `traceId` and `errors` one by one. An unknown case is reported, without its body, through
 * `report`.
 */
export async function readProblem(
  response: Response,
  report: (finding: UnknownProblemFinding) => void = reportUnknownProblem,
): Promise<ApiProblem> {
  const { status } = response;
  const body = await bodyOf(response);
  const code = codeOf(body.type);
  const traceId = typeof body.traceId === "string" ? { traceId: body.traceId } : {};

  if (code === undefined) {
    report(typeof body.type === "string" ? { status, type: stripQueryAndFragment(body.type) } : { status });
    return { status, code: "unknown", errors: [], ...traceId };
  }
  return {
    status,
    code,
    errors: code === "validation-failed" ? fieldErrorsOf(body.errors) : [],
    ...traceId,
    ...(code === "too-many-requests" ? { retryAfterSeconds: retryAfterSecondsOf(response.headers.get("Retry-After")) } : {}),
  };
}
