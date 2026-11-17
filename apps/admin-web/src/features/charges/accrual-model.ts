import type { AdminKey } from "../../i18n/resources.ts";
import type { AccrualPreview, AccrualRunStatus } from "../../shared/api/charges-operations.ts";

/** How often a run that has not finished is read again, in milliseconds. */
export const RUN_POLL_MS = 2000;

/** The states a run is still going through: it is read again until it leaves them. */
const ACTIVE_STATUSES: readonly AccrualRunStatus[] = ["QUEUED", "RUNNING"];

/** Whether the run has not reached a terminal state yet, so the screen keeps asking the server how it goes. */
export const isActiveRun = (status: AccrualRunStatus | undefined): boolean => status !== undefined && ACTIVE_STATUSES.includes(status);

/** The interval of `refetchInterval` for a run in this state: a number while it advances, `false` once it is terminal. */
export const pollIntervalOf = (status: AccrualRunStatus | undefined): number | false => (isActiveRun(status) ? RUN_POLL_MS : false);

/** The status of a run that is shown with the stronger warning: it did not complete what it was asked. */
export const isFailedRun = (status: AccrualRunStatus): boolean => status === "REJECTED" || status === "FAILED";

/** The error codes of an item that this screen has a text for. */
export const KNOWN_ITEM_ERROR_CODES = [
  "NO_PRICE",
  "AMBIGUOUS_PRICE",
  "TAX_RATE_MISSING",
  "TAX_RATE_INACTIVE",
  "TAX_RATE_NOT_IN_FORCE",
  "CONCEPT_INACTIVE",
  "STUDENT_INACTIVE",
  "ACCOUNT_UNDER_REVIEW",
  "OVERLAPPING_CHARGE",
  "POSTING_FAILED",
] as const;

export type KnownItemErrorCode = (typeof KNOWN_ITEM_ERROR_CODES)[number];

/** The code as the screen knows it: a code the server adds later reads as `unknown` and never as a blank. */
export function itemErrorOf(code: string | null | undefined): KnownItemErrorCode | "unknown" {
  return KNOWN_ITEM_ERROR_CODES.find((known) => known === code) ?? "unknown";
}

export const itemErrorKeyOf = (code: string | null | undefined): AdminKey => `charges.accrual.errorCodes.${itemErrorOf(code)}`;
export const itemErrorHelpKeyOf = (code: string | null | undefined): AdminKey => `charges.accrual.errorHelp.${itemErrorOf(code)}`;

/** What would make a run refuse, as the server names it. */
export const KNOWN_BLOCKERS = ["plan-not-active", "posting-accounts-incomplete", "plan-target-outside-year"] as const;
export type KnownBlocker = (typeof KNOWN_BLOCKERS)[number];

export function blockerOf(code: string): KnownBlocker | "unknown" {
  return KNOWN_BLOCKERS.find((known) => known === code) ?? "unknown";
}

export const blockerKeyOf = (code: string): AdminKey => `charges.accrual.blockers.${blockerOf(code)}`;

/** Why a rejected or failed run did not run, as the server names it. */
export const KNOWN_FAILURE_CODES = [
  "plan-not-active",
  "period-not-in-plan",
  "posting-accounts-incomplete",
  "period-in-future",
  "plan-target-outside-year",
  "preview-outdated",
  "retries-exhausted",
] as const;
export type KnownFailureCode = (typeof KNOWN_FAILURE_CODES)[number];

export function failureCodeOf(code: string): KnownFailureCode | "unknown" {
  return KNOWN_FAILURE_CODES.find((known) => known === code) ?? "unknown";
}

export const failureKeyOf = (code: string): AdminKey => `charges.run.failureCodes.${failureCodeOf(code)}`;

/** What the person chose to generate: a plan and one of its periods. */
export interface AccrualSelection {
  readonly planId: string;
  readonly periodStart: string;
}

/**
 * What the person confirmed. The key is made once for the intent and sent on every try of it: a retry after a network
 * failure (the server may or may not have queued the run) answers the same run and never a second one. Another
 * confirmation, even of the same period, is another intent with its own key.
 */
export interface AccrualIntent extends AccrualSelection {
  readonly idempotencyKey: string;
  /** The digest of the preview the person saw, so the server refuses the run if the calculation moved since. */
  readonly previewDigest: string;
}

export function newIntent(selection: AccrualSelection, preview: Pick<AccrualPreview, "previewDigest">, random: () => string = () => crypto.randomUUID()): AccrualIntent {
  return { ...selection, previewDigest: preview.previewDigest, idempotencyKey: random() };
}

/** The request body of an intent: the period and the digest, nothing else. */
export const runBodyOf = (intent: AccrualIntent): { periodStart: string; previewDigest: string } => ({
  periodStart: intent.periodStart,
  previewDigest: intent.previewDigest,
});

/** Whether the preview on screen is of what is chosen now. A preview of another selection is never offered for confirmation. */
export const previewMatches = (preview: Pick<AccrualPreview, "planId" | "periodStart">, selection: Partial<AccrualSelection>): boolean =>
  preview.planId === selection.planId && preview.periodStart === selection.periodStart;

/** Rows of the preview shown at a time: the whole preview (500 rows at most) is one answer of the server, shown by pages. */
export const PREVIEW_PAGE_SIZE = 25;

/** The filter of the preview rows: every row, the rows with any error, or the rows with one error code. */
export const ALL_ROWS = "";
export const ERROR_ROWS = "ERRORS";

export interface PreviewRow {
  readonly outcome: string;
  readonly errorCode?: string | null | undefined;
}

/** The rows the filter keeps. The rows are the ones the server sent in the preview; the filter only chooses which to show. */
export function filterPreviewRows<Row extends PreviewRow>(rows: readonly Row[], filter: string): readonly Row[] {
  if (filter === ALL_ROWS) return rows;
  if (filter === ERROR_ROWS) return rows.filter((row) => row.outcome === "ERROR");
  return rows.filter((row) => row.errorCode === filter);
}

/** One page of rows already in memory, counting from 0. */
export function pageOfRows<Row>(rows: readonly Row[], page: number, size: number): readonly Row[] {
  return rows.slice(page * size, (page + 1) * size);
}

/** The label of a month period as the server names it, `2027-02`, read from the first day of the period. */
export const periodLabelOf = (periodStart: string): string => periodStart.slice(0, 7);
