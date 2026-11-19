import {
  AccrualPeriodResponse,
  AccrualPreviewResponse,
  AccrualRunDetailResponse,
  AccrualRunPageResponse,
  AccrualRunResponse,
  ChargePlanDetailResponse,
  ChargePlanItemResponse,
  ChargePlanPageResponse,
  ChargePlanResponse,
  ChargeVoidResponse,
  IntegrityRunDetailResponse,
  IntegrityRunPageResponse,
  StudentChargePageResponse,
  StudentStatement,
  type AccrualPeriodResponseOutput,
  type AccrualPreviewRequest,
  type AccrualPreviewResponseOutput,
  type AccrualRunDetailResponseOutput,
  type AccrualRunItemResponseOutput,
  type AccrualRunPageResponseOutput,
  type AccrualRunRequest,
  type AccrualRunResponseOutput,
  type AddChargePlanItemRequest,
  type ChargePlanDetailResponseOutput,
  type ChargePlanItemResponseOutput,
  type ChargePlanPageResponseOutput,
  type ChargePlanResponseOutput,
  type ChargeVoidBody,
  type ChargeVoidResponseOutput,
  type CreateChargePlanRequest,
  type IntegrityFindingResponseOutput,
  type IntegrityRunDetailResponseOutput,
  type IntegrityRunPageResponseOutput,
  type IntegrityRunResponseOutput,
  type StudentChargePageResponseOutput,
  type StudentChargeResponseOutput,
  type StudentStatementLineOutput,
  type StudentStatementOutput,
  type StudentStatementSectionOutput,
  type UpdateChargePlanRequest,
} from "@confia/contracts/admin";
import { request, requestEmpty, type ApiResult, type ResponseSchema } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/charges`. Each takes the access token as a parameter, like the operations of access: this
 * module knows no session. The plans move no money (a plan books nothing in the ledger and holds no price), so none of their
 * operations sends an `Idempotency-Key`; the request of an accrual run does, because it writes the ledger. Paging, order and filters are the server's: they travel as query parameters and nothing here, or
 * after it, sorts or filters a page in the browser.
 */
const { list, create, update } = resourceClient("/api/v1/charges");

export type { PageParams };

export type ChargePlanScope = ChargePlanResponseOutput["targetScope"];
export type ChargePlanStatus = ChargePlanResponseOutput["status"];
export type ChargePlanFrequency = ChargePlanResponseOutput["frequency"];

export interface ChargePlanListParams extends PageParams {
  readonly academicYearId?: string | undefined;
  readonly targetScope?: ChargePlanScope | undefined;
  readonly status?: ChargePlanStatus | undefined;
}

const planPath = (id: string): string => `/api/v1/charges/plans/${encodeURIComponent(id)}`;
const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });

export const listChargePlans = (token: string, params: ChargePlanListParams): Promise<ApiResult<ChargePlanPageResponseOutput>> =>
  list("plans", ChargePlanPageResponse, token, params);

/** A new plan: it is born a draft. */
export const createChargePlan = (token: string, body: CreateChargePlanRequest): Promise<ApiResult<ChargePlanResponseOutput>> =>
  create("plans", ChargePlanResponse, token, body);

/** One plan with its items. */
export const getChargePlan = (token: string, id: string): Promise<ApiResult<ChargePlanDetailResponseOutput>> =>
  request({ method: "GET", path: planPath(id), headers: bearer(token), schema: ChargePlanDetailResponse });

/** The name, the frequency, the due day and the status of a plan, with the version it was read at. */
export const updateChargePlan = (token: string, id: string, body: UpdateChargePlanRequest): Promise<ApiResult<ChargePlanResponseOutput>> =>
  update("plans", ChargePlanResponse, token, id, body);

/** A new item of a draft plan: a concept, how many periods it accrues and the first day of the first one. */
export const addChargePlanItem = (token: string, planId: string, body: AddChargePlanItemRequest): Promise<ApiResult<ChargePlanItemResponseOutput>> =>
  request({ method: "POST", path: `${planPath(planId)}/items`, headers: bearer(token), body, schema: ChargePlanItemResponse });

/** Removes an item of a draft plan: the server answers `204` with no body. */
export const removeChargePlanItem = (token: string, planId: string, itemId: string): Promise<ApiResult<undefined>> =>
  requestEmpty({ method: "DELETE", path: `${planPath(planId)}/items/${encodeURIComponent(itemId)}`, headers: bearer(token) });

// The accrual engine (charges-accrual cut): the periods of a plan, the preview of one, the request of a run and the reading
// of runs. The amounts of the preview travel as `{ amount, currency }` strings the server has already rounded: nothing here
// parses, adds or rounds them.

export type AccrualPeriod = AccrualPeriodResponseOutput;
export type AccrualPreview = AccrualPreviewResponseOutput;
export type AccrualRun = AccrualRunResponseOutput;
export type AccrualRunDetail = AccrualRunDetailResponseOutput;
export type AccrualRunItem = AccrualRunItemResponseOutput;
export type AccrualRunPage = AccrualRunPageResponseOutput;
export type AccrualRunStatus = AccrualRunResponseOutput["status"];
export type AccrualOutcome = AccrualRunItemResponseOutput["outcome"];

/**
 * The server writes an absent value as `null` (the API keeps Jackson's default of including nulls), while the generated
 * schema of a nested object (`lastRun`, and `base`, `tax` and `total` of an item) accepts only an absent key. A null is
 * the same fact as an absent key, so it is dropped before the schema reads the body; nothing else changes.
 */
function withoutNulls(value: unknown): unknown {
  if (Array.isArray(value)) return (value as unknown[]).map(withoutNulls);
  if (typeof value === "object" && value !== null) {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([, member]) => member !== null)
        .map(([name, member]) => [name, withoutNulls(member)]),
    );
  }
  return value;
}

function lenient<T>(schema: ResponseSchema<T>): ResponseSchema<T> {
  return { safeParse: (input) => schema.safeParse(withoutNulls(input)) };
}

/** A list of `schema` rows, each read as the schema says: one that does not meet it fails the whole answer. */
function listOf<T>(schema: ResponseSchema<T>): ResponseSchema<T[]> {
  return {
    safeParse(input) {
      const rows: T[] = [];
      if (!Array.isArray(input)) return { success: false };
      for (const row of input as unknown[]) {
        const parsed = schema.safeParse(withoutNulls(row));
        if (!parsed.success) return { success: false };
        rows.push(parsed.data);
      }
      return { success: true, data: rows };
    },
  };
}

export interface AccrualRunListParams {
  readonly page: number;
  readonly size: number;
  readonly planId?: string | undefined;
}

export interface AccrualItemsParams {
  readonly page: number;
  readonly size: number;
}

/** The periods the plan accrues, each with its most recent run. The server derives them: the browser never computes a period. */
export const listAccrualPeriods = (token: string, planId: string): Promise<ApiResult<AccrualPeriod[]>> =>
  request({ method: "GET", path: `${planPath(planId)}/accrual-periods`, headers: bearer(token), schema: listOf(AccrualPeriodResponse) });

/** What accruing a period would do, with nothing written, and the digest to send back when the person confirms. */
export const previewAccrual = (token: string, planId: string, body: AccrualPreviewRequest): Promise<ApiResult<AccrualPreview>> =>
  request({ method: "POST", path: `${planPath(planId)}/accrual-preview`, headers: bearer(token), body, schema: lenient(AccrualPreviewResponse) });

/**
 * Asks for the accrual of a period: the server answers `202` with the run, queued. It writes the ledger, so the request
 * carries the `Idempotency-Key` of the person's intent: the same key and the same body answer the same run, whatever the
 * number of tries.
 */
export const requestAccrualRun = (token: string, planId: string, body: AccrualRunRequest, idempotencyKey: string): Promise<ApiResult<AccrualRun>> =>
  request({
    method: "POST",
    path: `${planPath(planId)}/accrual-runs`,
    headers: { ...bearer(token), "Idempotency-Key": idempotencyKey },
    body,
    schema: lenient(AccrualRunResponse),
  });

/** One page of runs, most recent first, optionally of one plan. The order is the server's: it takes no `sort`. */
export const listAccrualRuns = (token: string, params: AccrualRunListParams): Promise<ApiResult<AccrualRunPage>> =>
  list("accrual-runs", lenient(AccrualRunPageResponse), token, params);

/** One run with one page of its items (what happened to each student and concept). */
export const getAccrualRun = (token: string, runId: string, params: AccrualItemsParams): Promise<ApiResult<AccrualRunDetail>> =>
  request({
    method: "GET",
    path: `/api/v1/charges/accrual-runs/${encodeURIComponent(runId)}?page=${String(params.page)}&size=${String(params.size)}`,
    headers: bearer(token),
    schema: lenient(AccrualRunDetailResponse),
  });

// The statement of account of a student (ledger-account-statement cut): read only. Every amount, the running balance of each line
// and the opening and closing balances are `{ amount, currency }` strings the server computed and rounded; nothing here or after
// it adds, subtracts or rounds them.

export type StudentStatementResponse = StudentStatementOutput;
export type StatementSection = StudentStatementSectionOutput;
export type StatementLine = StudentStatementLineOutput;
export type StatementLineKind = StudentStatementLineOutput["kind"];

export interface StatementParams {
  /** Absent: one section per currency the student has. */
  readonly currency?: string | undefined;
  /** `yyyy-MM-dd`; absent: the last 12 months. */
  readonly from?: string | undefined;
  readonly to?: string | undefined;
  /** Counted from 0; it pages the lines of every section. */
  readonly page: number;
  readonly size: number;
}

/** The statement of one student: a section per currency, each with its balances and one page of lines. */
export const getStudentStatement = (token: string, studentId: string, params: StatementParams): Promise<ApiResult<StudentStatementResponse>> => {
  const query = new URLSearchParams();
  if (params.currency !== undefined) query.set("currency", params.currency);
  if (params.from !== undefined) query.set("from", params.from);
  if (params.to !== undefined) query.set("to", params.to);
  query.set("page", String(params.page));
  query.set("size", String(params.size));
  return request({
    method: "GET",
    path: `/api/v1/charges/students/${encodeURIComponent(studentId)}/statement?${query.toString()}`,
    headers: bearer(token),
    schema: lenient(StudentStatement),
  });
};

// The charges of a student and their void (charges-void-and-integrity cut). The amounts travel as `{ amount, currency }` strings the
// server already rounded; the status of a charge (`ACTIVE` or `VOIDED`) is derived by the server from the void row.

export type StudentCharge = StudentChargeResponseOutput;
export type StudentChargePage = StudentChargePageResponseOutput;
export type StudentChargeStatus = StudentChargeResponseOutput["status"];
export type ChargeVoid = ChargeVoidResponseOutput;

export interface StudentChargesParams extends PageParams {
  readonly status?: StudentChargeStatus | undefined;
  /** `yyyy-MM-dd`: the first day of the period, from this date included. */
  readonly periodFrom?: string | undefined;
  /** `yyyy-MM-dd`: the first day of the period, up to this date included. */
  readonly periodTo?: string | undefined;
}

/** One page of the charges of a student, as the server pages, filters and orders it. */
export const listStudentCharges = (token: string, studentId: string, params: StudentChargesParams): Promise<ApiResult<StudentChargePage>> => {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(params) as [string, unknown][]) {
    if (typeof value === "string" || typeof value === "number") query.set(name, String(value));
  }
  return request({
    method: "GET",
    path: `/api/v1/charges/students/${encodeURIComponent(studentId)}/charges?${query.toString()}`,
    headers: bearer(token),
    schema: lenient(StudentChargePageResponse),
  });
};

/**
 * Voids a charge: the server posts the exact mirror of its entry in the ledger. It moves money, so the request carries the
 * `Idempotency-Key` of the person's intent: the same key and the same body answer the same void, whatever the number of tries.
 */
export const voidCharge = (token: string, chargeId: string, body: ChargeVoidBody, idempotencyKey: string): Promise<ApiResult<ChargeVoid>> =>
  request({
    method: "POST",
    path: `/api/v1/charges/${encodeURIComponent(chargeId)}/void`,
    headers: { ...bearer(token), "Idempotency-Key": idempotencyKey },
    body,
    schema: lenient(ChargeVoidResponse),
  });

// The nightly integrity check (read only): the runs, the latest one and one run with its discrepancies.

export type IntegrityRun = IntegrityRunResponseOutput;
export type IntegrityRunPage = IntegrityRunPageResponseOutput;
export type IntegrityRunDetail = IntegrityRunDetailResponseOutput;
export type IntegrityFinding = IntegrityFindingResponseOutput;
export type IntegrityRunStatus = IntegrityRunResponseOutput["status"];
export type IntegrityKind = IntegrityFindingResponseOutput["kind"];

export interface IntegrityRunsParams {
  readonly page: number;
  readonly size: number;
}

export const listIntegrityRuns = (token: string, params: IntegrityRunsParams): Promise<ApiResult<IntegrityRunPage>> =>
  list("integrity-runs", lenient(IntegrityRunPageResponse), token, params);

/** The most recent run with its discrepancies; `404` while the check has not run for the institution. */
export const getLatestIntegrityRun = (token: string): Promise<ApiResult<IntegrityRunDetail>> =>
  request({ method: "GET", path: "/api/v1/charges/integrity-runs/latest", headers: bearer(token), schema: lenient(IntegrityRunDetailResponse) });

export const getIntegrityRun = (token: string, runId: string): Promise<ApiResult<IntegrityRunDetail>> =>
  request({ method: "GET", path: `/api/v1/charges/integrity-runs/${encodeURIComponent(runId)}`, headers: bearer(token), schema: lenient(IntegrityRunDetailResponse) });
