import { createHash } from "node:crypto";
import { AccrualRunRequest } from "@confia/contracts/admin";
import {
  accrualPeriodsRecord,
  accrualPreviewRecord,
  accrualRunDetailRecord,
  accrualRunPage,
  accrualRunRecord,
  problem,
  type ContractResponse,
} from "../../src/test/contract-responses.ts";
import type { PaymentConceptRow } from "./catalog-seed.ts";
import type { ChargePlanItemRow, ChargePlanRow, ChargePlans } from "./charges-seed.ts";
import type { ModelResponse } from "./session-model.ts";
import type { StudentRow } from "./students-seed.ts";

/**
 * The accrual engine of the simulated API (charges-accrual cut): the five operations of the engine over the plans, the students and
 * the concepts of the same scenario. A thin simulation of the published shapes and of the rules a journey needs, nothing more: the
 * periods a plan lists, a preview with its digest and its blockers, a request that needs an `Idempotency-Key` (the same key and the
 * same body answer the same run, another body answers 422, another key while a run of the period is queued or running answers 409),
 * and a run that goes QUEUED, RUNNING and then terminal over three reads, as the worker would. The real rules (price resolution,
 * tax, the ledger) are the backend's: here a student in scope, a price and an error are fixed by their position. The amounts are
 * strings in cents arithmetic: this file plays the server, so it is the one place a sum of money is allowed.
 */
export type AccrualScenario = "default" | "empty" | "blocked" | "digest-changed" | "in-progress" | "forbidden" | "preview-fails-first";

export interface AccrualRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly body: unknown;
  /** The headers of the request, with their names in lower case. */
  readonly headers?: Readonly<Record<string, string | undefined>>;
}

export interface RunRow {
  id: string;
  planId: string;
  periodStart: string;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "COMPLETED_WITH_ERRORS" | "REJECTED" | "FAILED";
  previewChecked: boolean;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  studentsEvaluated: number;
  chargesCreated: number;
  chargesExisting: number;
  chargesFailed: number;
  failureCode: string | null;
  /** What the request sent: the digest of the preview, kept to compare when the run finishes. */
  previewDigest: string | null;
  /** How many times the run has been read: the third read finds it terminal. */
  reads: number;
  items: RunItemRow[];
}

export interface RunItemRow {
  studentId: string;
  paymentConceptId: string;
  outcome: "CREATED" | "ALREADY_ACCRUED" | "ALREADY_ACCRUED_OTHER_PLAN" | "ERROR";
  errorCode: string | null;
  chargeId: string | null;
  existingChargeId: string | null;
}

export interface AccrualSimulator {
  handle(request: AccrualRequest): ModelResponse | undefined;
  /** What the scenario holds now, for a unit test; never the page. */
  runs(): readonly RunRow[];
}

export interface AccrualContext {
  readonly plans: () => ChargePlans;
  readonly students: () => readonly StudentRow[];
  readonly concepts: () => readonly PaymentConceptRow[];
  /** The failure the reads answer first in the `failing-first` behavior, or `undefined`. */
  readonly readFailure: () => ModelResponse | undefined;
  readonly now: () => Date;
}

/** How many students a preview evaluates: the first active ones of the registry. */
export const STUDENTS_IN_SCOPE = 12;
const TRACE_ID = "01J8Z4K2QF9P0X";
const MAX_SIZE = 200;
const DEFAULT_SIZE = 50;
const PLAN_ROUTE = /^\/api\/v1\/charges\/plans\/([^/]+)\/(accrual-periods|accrual-preview|accrual-runs)$/;
const RUN_ROUTE = /^\/api\/v1\/charges\/accrual-runs(?:\/([^/]+))?$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STEP: Record<ChargePlanRow["frequency"], number> = { MONTHLY: 1, BIMONTHLY: 2, ANNUAL: 12, ONE_TIME: 1 };
/** The tax rate of the taxable concepts of the simulation, in percent. */
const TAX_PERCENT = 15;

/** A valid UUID (version 4, RFC variant) that ends in the number given, so the seeded runs read at a glance. */
export const runId = (n: number): string => `00000000-0000-4000-8000-${(0xa000 + n).toString(16).padStart(12, "0")}`;
const chargeId = (n: number): string => `00000000-0000-4000-8000-${(0xc000 + n).toString(16).padStart(12, "0")}`;

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });
const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse =>
  wrap(problem(code, status, options));
/** A null is an absent key to the generated schemas, so the body is checked without its nulls and served with them. */
function withoutNulls(value: unknown): unknown {
  if (Array.isArray(value)) return (value as unknown[]).map(withoutNulls);
  if (typeof value === "object" && value !== null) {
    return Object.fromEntries(Object.entries(value).filter(([, member]) => member !== null).map(([name, member]) => [name, withoutNulls(member)]));
  }
  return value;
}

/** Serves the body the way the server writes it, nulls included, after the contract has accepted it. */
function served(check: (body: unknown) => ContractResponse, wire: unknown): ModelResponse {
  return wrap({ ...check(withoutNulls(wire)), body: wire });
}

const invalid = (errors: { field: string; reason: string }[] = []): ModelResponse => failure("validation-failed", 400, { errors });

/** A month period as `YYYY-MM-01`, `n` months after `start`. */
function addMonths(start: string, n: number): string {
  const [year = 0, month = 1] = start.split("-").map(Number);
  const index = year * 12 + (month - 1) + n;
  return `${String(Math.floor(index / 12))}-${String((index % 12) + 1).padStart(2, "0")}-01`;
}

/** Cents as the two-decimal string the server writes. */
const money = (cents: number): { amount: string; currency: string } => ({
  amount: `${String(Math.floor(cents / 100))}.${String(cents % 100).padStart(2, "0")}`,
  currency: "HNL",
});

/** The price of a concept in cents, fixed by its number in the seed: 1500.00 for the first, 1750.00 for the second, and so on. */
const baseCentsOf = (index: number): number => 125_000 + 25_000 * (index + 1);

/** 15 % of the base, rounded half up to the cent, only for a taxable concept. */
const taxCentsOf = (concept: PaymentConceptRow, base: number): number => (concept.isTaxable ? Math.floor((base * TAX_PERCENT + 50) / 100) : 0);

export function createAccrualSimulator(scenario: AccrualScenario, context: AccrualContext): AccrualSimulator {
  const runs: RunRow[] = [];
  /** The charges the runs created, by plan, period, student and concept. */
  const charges = new Set<string>();
  const keys = new Map<string, { body: string; runId: string }>();
  let epoch = 0;
  let previewsServed = 0;
  let sequence = 0;

  const chargeKey = (planId: string, period: string, student: string, concept: string) => `${planId}|${period}|${student}|${concept}`;

  function periodsOf(plan: ChargePlanRow): { periodStart: string; concepts: string[] }[] {
    const found = new Map<string, string[]>();
    for (const item of context.plans().items.filter((row: ChargePlanItemRow) => row.planId === plan.id)) {
      const step = STEP[plan.frequency];
      for (let n = 0; n < item.periodsCount; n += 1) {
        const start = addMonths(item.firstPeriodOn, n * step);
        found.set(start, [...(found.get(start) ?? []), item.paymentConceptId]);
      }
    }
    return [...found.entries()].sort(([a], [b]) => (a < b ? -1 : 1)).map(([periodStart, concepts]) => ({ periodStart, concepts }));
  }

  const scope = (): readonly StudentRow[] => context.students().filter((row) => row.status === "ACTIVE").slice(0, STUDENTS_IN_SCOPE);

  /** What accruing a period would do for each student and concept, in the order of the students. */
  function evaluate(plan: ChargePlanRow, period: { periodStart: string; concepts: string[] }) {
    const concepts = context.concepts();
    const rows: (RunItemRow & { base?: number; tax?: number })[] = [];
    scope().forEach((student, studentIndex) => {
      for (const id of period.concepts) {
        const concept = concepts.find((row) => row.id === id);
        const index = concepts.findIndex((row) => row.id === id);
        if (concept === undefined) continue;
        const item: RunItemRow & { base?: number; tax?: number } = { studentId: student.id, paymentConceptId: id, outcome: "CREATED", errorCode: null, chargeId: null, existingChargeId: null };
        const existing = charges.has(chargeKey(plan.id, period.periodStart, student.id, id));
        // The first concept of the seed carries the rules a journey needs: no price, an ambiguous price and a missing tax rate.
        const error = index === 0 ? ({ 2: "NO_PRICE", 5: "AMBIGUOUS_PRICE", 8: "TAX_RATE_MISSING" } as Record<number, string>)[studentIndex] : undefined;
        if (existing) {
          Object.assign(item, { outcome: "ALREADY_ACCRUED", existingChargeId: chargeId(rows.length + 1) });
        } else if (error !== undefined) {
          Object.assign(item, { outcome: "ERROR", errorCode: error });
        } else {
          const base = baseCentsOf(index);
          Object.assign(item, { base, tax: taxCentsOf(concept, base) });
        }
        rows.push(item);
      }
    });
    return rows;
  }

  const digestOf = (plan: ChargePlanRow, periodStart: string, rows: readonly RunItemRow[]): string =>
    createHash("sha256")
      .update(JSON.stringify({ planId: plan.id, periodStart, epoch, outcomes: rows.map((row) => [row.studentId, row.paymentConceptId, row.outcome, row.errorCode]) }))
      .digest("hex");

  function previewBody(plan: ChargePlanRow, period: { periodStart: string; concepts: string[] }) {
    const rows = evaluate(plan, period);
    const created = rows.filter((row) => row.outcome === "CREATED");
    const base = created.reduce((sum, row) => sum + (row.base ?? 0), 0);
    const tax = created.reduce((sum, row) => sum + (row.tax ?? 0), 0);
    const errorsByCode: Record<string, number> = {};
    for (const row of rows) if (row.errorCode !== null) errorsByCode[row.errorCode] = (errorsByCode[row.errorCode] ?? 0) + 1;
    const blockers = [...(plan.status === "ACTIVE" ? [] : ["plan-not-active"]), ...(scenario === "blocked" ? ["posting-accounts-incomplete"] : [])];
    return {
      planId: plan.id,
      periodStart: period.periodStart,
      studentsEvaluated: scope().length,
      toCreate: created.length,
      alreadyAccrued: rows.filter((row) => row.outcome === "ALREADY_ACCRUED").length,
      alreadyAccruedOtherPlan: 0,
      errors: rows.filter((row) => row.outcome === "ERROR").length,
      totals: created.length === 0 ? [] : [{ currency: "HNL", charges: created.length, base: money(base), benefitAmount: money(0), netBase: money(base), tax: money(tax), total: money(base + tax) }],
      errorsByCode,
      items: rows.slice(0, 500).map((row) => ({
        studentId: row.studentId,
        paymentConceptId: row.paymentConceptId,
        outcome: row.outcome,
        errorCode: row.errorCode,
        ...(row.base === undefined ? { base: null, benefitAmount: null, netBase: null, tax: null, total: null } : { base: money(row.base), benefitAmount: money(0), netBase: money(row.base), tax: money(row.tax ?? 0), total: money(row.base + (row.tax ?? 0)) }),
        existingChargeId: row.existingChargeId,
      })),
      itemsTruncated: rows.length > 500,
      blockers,
      previewDigest: digestOf(plan, period.periodStart, rows),
    };
  }

  /** The run as the API writes it: the nulls of an absent value are written, like the server does. */
  const runBody = (run: RunRow) => ({
    id: run.id,
    planId: run.planId,
    periodStart: run.periodStart,
    status: run.status,
    previewChecked: run.previewChecked,
    queuedAt: run.queuedAt,
    startedAt: run.startedAt,
    finishedAt: run.finishedAt,
    studentsEvaluated: run.studentsEvaluated,
    chargesCreated: run.chargesCreated,
    chargesExisting: run.chargesExisting,
    chargesFailed: run.chargesFailed,
    failureCode: run.failureCode,
  });


  /** Moves a run one step forward the way the worker would: queued, running, then terminal. It runs after a read answers. */
  function advance(run: RunRow): void {
    const now = context.now().toISOString();
    if (run.status === "QUEUED") {
      Object.assign(run, { status: "RUNNING", startedAt: now, studentsEvaluated: Math.floor(STUDENTS_IN_SCOPE / 2) });
      return;
    }
    if (run.status !== "RUNNING") return;
    const plan = context.plans().plans.find((row) => row.id === run.planId);
    const period = plan === undefined ? undefined : periodsOf(plan).find((row) => row.periodStart === run.periodStart);
    if (plan === undefined || period === undefined) {
      Object.assign(run, { status: "FAILED", finishedAt: now, failureCode: "period-not-in-plan" });
      return;
    }
    const rows = evaluate(plan, period);
    if (run.previewDigest !== null && run.previewDigest !== digestOf(plan, run.periodStart, rows)) {
      // The calculation moved since the preview: nothing is written and the run says why.
      Object.assign(run, { status: "REJECTED", finishedAt: now, studentsEvaluated: 0, failureCode: "preview-outdated" });
      return;
    }
    finish(run, plan, rows, now);
  }

  function finish(run: RunRow, plan: ChargePlanRow, rows: ReturnType<typeof evaluate>, now: string): void {
    const items: RunItemRow[] = rows.map((row) => {
      if (row.outcome !== "CREATED") return { studentId: row.studentId, paymentConceptId: row.paymentConceptId, outcome: row.outcome, errorCode: row.errorCode, chargeId: null, existingChargeId: row.existingChargeId };
      sequence += 1;
      charges.add(chargeKey(plan.id, run.periodStart, row.studentId, row.paymentConceptId));
      return { studentId: row.studentId, paymentConceptId: row.paymentConceptId, outcome: "CREATED", errorCode: null, chargeId: chargeId(sequence), existingChargeId: null };
    });
    const failed = items.filter((item) => item.outcome === "ERROR").length;
    Object.assign(run, {
      status: failed > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED",
      finishedAt: now,
      studentsEvaluated: scope().length,
      chargesCreated: items.filter((item) => item.outcome === "CREATED").length,
      chargesExisting: items.filter((item) => item.outcome === "ALREADY_ACCRUED").length,
      chargesFailed: failed,
      items,
    });
  }

  /** The runs the scenario starts with, on the active plan of the seed: one completed, one with errors, one rejected, one failed. */
  function seedRuns(): void {
    const plan = context.plans().plans.find((row) => row.status === "ACTIVE");
    if (plan === undefined) return;
    const periods = periodsOf(plan);
    const stamp = (n: number) => `2026-11-0${String(n)}T15:00:00Z`;
    const make = (n: number, periodIndex: number, status: RunRow["status"], failureCode: string | null = null): void => {
      const period = periods[periodIndex];
      if (period === undefined) return;
      const run: RunRow = {
        id: runId(n),
        planId: plan.id,
        periodStart: period.periodStart,
        status: "RUNNING",
        previewChecked: true,
        queuedAt: stamp(n),
        startedAt: stamp(n),
        finishedAt: null,
        studentsEvaluated: 0,
        chargesCreated: 0,
        chargesExisting: 0,
        chargesFailed: 0,
        failureCode: null,
        previewDigest: null,
        reads: 0,
        items: [],
      };
      if (status === "COMPLETED" || status === "COMPLETED_WITH_ERRORS") finish(run, plan, evaluate(plan, period), stamp(n));
      else Object.assign(run, { status, finishedAt: stamp(n), failureCode });
      run.finishedAt = stamp(n);
      runs.push(run);
    };
    make(1, 0, "COMPLETED");
    make(2, 1, "COMPLETED_WITH_ERRORS");
    make(3, 2, "REJECTED", "preview-outdated");
    make(4, 3, "FAILED", "retries-exhausted");
  }
  if (scenario !== "empty") seedRuns();

  function periodsResponse(plan: ChargePlanRow): ModelResponse {
    const failed = context.readFailure();
    if (failed !== undefined) return failed;
    const rows = periodsOf(plan).map(({ periodStart, concepts }) => {
      const last = [...runs].reverse().find((run) => run.planId === plan.id && run.periodStart === periodStart);
      const label = periodStart.slice(0, 7);
      return {
        periodStart,
        label,
        dueOn: `${label}-${String(plan.dueDayOfMonth).padStart(2, "0")}`,
        paymentConceptIds: concepts,
        // The server writes `"lastRun": null` for a period that has none.
        lastRun: last === undefined ? null : runBody(last),
      };
    });
    return served((checked) => accrualPeriodsRecord(checked as unknown[]), rows);
  }

  function previewResponse(plan: ChargePlanRow, body: unknown): ModelResponse {
    if (scenario === "forbidden") return failure("forbidden", 403);
    const period = typeof (body as { periodStart?: unknown } | undefined)?.periodStart === "string" ? periodsOf(plan).find((row) => row.periodStart === (body as { periodStart: string }).periodStart) : undefined;
    if (period === undefined) return invalid([{ field: "periodStart", reason: "invalid" }]);
    if (scenario === "preview-fails-first" && previewsServed === 0) {
      previewsServed += 1;
      return failure("internal-error", 500, { traceId: TRACE_ID });
    }
    previewsServed += 1;
    const answer = previewBody(plan, period);
    // The calculation moves once the preview is out: the digest the person confirms is then stale.
    if (scenario === "digest-changed" && previewsServed === 1) epoch += 1;
    return served(accrualPreviewRecord, answer);
  }

  function requestRun(plan: ChargePlanRow, body: unknown, key: string | undefined): ModelResponse {
    if (scenario === "forbidden") return failure("forbidden", 403);
    if (key === undefined || key.trim() === "") return failure("idempotency-key-missing", 400);
    if (key.length > 128) return invalid([{ field: "Idempotency-Key", reason: "size" }]);
    const parsed = AccrualRunRequest.safeParse(body);
    if (!parsed.success) return invalid(parsed.error.issues.map((issue) => ({ field: issue.path.map(String).join(".") || "body", reason: "invalid" })));
    const { periodStart, previewDigest } = parsed.data;
    const fingerprint = JSON.stringify({ planId: plan.id, periodStart, previewDigest: previewDigest ?? null });
    const known = keys.get(key);
    if (known !== undefined) {
      if (known.body !== fingerprint) return failure("idempotency-payload-mismatch", 422);
      const same = runs.find((run) => run.id === known.runId);
      if (same !== undefined) return served((checked) => accrualRunRecord(checked), runBody(same));
    }
    if (!periodsOf(plan).some((row) => row.periodStart === periodStart)) return invalid([{ field: "periodStart", reason: "invalid" }]);
    if (plan.status !== "ACTIVE") return failure("validation-failed", 400);
    if (scenario === "in-progress" || runs.some((run) => run.planId === plan.id && run.periodStart === periodStart && (run.status === "QUEUED" || run.status === "RUNNING"))) {
      return failure("accrual-run-in-progress", 409);
    }
    sequence += 1;
    const run: RunRow = {
      id: runId(100 + sequence),
      planId: plan.id,
      periodStart,
      status: "QUEUED",
      previewChecked: previewDigest !== undefined && previewDigest !== null,
      queuedAt: context.now().toISOString(),
      startedAt: null,
      finishedAt: null,
      studentsEvaluated: 0,
      chargesCreated: 0,
      chargesExisting: 0,
      chargesFailed: 0,
      failureCode: null,
      previewDigest: previewDigest ?? null,
      reads: 0,
      items: [],
    };
    runs.push(run);
    keys.set(key, { body: fingerprint, runId: run.id });
    return served((checked) => accrualRunRecord(checked), runBody(run));
  }

  function listRuns(query: URLSearchParams): ModelResponse {
    const failed = context.readFailure();
    if (failed !== undefined) return failed;
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) return invalid([{ field: "page", reason: "invalid" }]);
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) return invalid([{ field: "size", reason: "invalid" }]);
    const planId = query.get("planId");
    if (planId !== null && planId !== "" && !UUID.test(planId)) return invalid([{ field: "planId", reason: "invalid" }]);
    const matching = runs
      .map((run, index) => ({ run, index }))
      .filter(({ run }) => planId === null || planId === "" || run.planId === planId)
      // Most recent first: by the time it was queued, and the later one first when it is a tie.
      .sort((a, b) => (a.run.queuedAt === b.run.queuedAt ? b.index - a.index : a.run.queuedAt < b.run.queuedAt ? 1 : -1))
      .map(({ run }) => run);
    const slice = matching.slice(page * size, (page + 1) * size).map((run) => runBody(run));
    return served((checked) => accrualRunPage((checked as { items: unknown[] }).items, { page, size, total: matching.length }), { items: slice, page, size, total: matching.length });
  }

  function getRun(id: string, query: URLSearchParams): ModelResponse {
    const failed = context.readFailure();
    if (failed !== undefined) return failed;
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) return invalid([{ field: "page", reason: "invalid" }]);
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) return invalid([{ field: "size", reason: "invalid" }]);
    const run = runs.find((row) => row.id === id);
    if (run === undefined) return failure("resource-not-found", 404);
    const items = run.items.slice(page * size, (page + 1) * size);
    const answer = served((checked) => accrualRunDetailRecord((checked as { run: unknown }).run, (checked as { items: unknown[] }).items, { page, size, total: run.items.length }), {
      run: runBody(run),
      items,
      page,
      size,
      totalItems: run.items.length,
    });
    // The answer shows the state the run is in; the worker then takes its next step.
    run.reads += 1;
    advance(run);
    return answer;
  }

  return {
    runs: () => runs,
    handle({ method, path, query, body, headers }) {
      const onPlan = PLAN_ROUTE.exec(path);
      if (onPlan !== null) {
        const [, id = "", operation] = onPlan;
        const plan = context.plans().plans.find((row) => row.id === id);
        if (operation === "accrual-periods") {
          if (method !== "GET") return undefined;
          return plan === undefined ? failure("resource-not-found", 404) : periodsResponse(plan);
        }
        if (method !== "POST") return undefined;
        if (plan === undefined) return failure("resource-not-found", 404);
        return operation === "accrual-preview" ? previewResponse(plan, body) : requestRun(plan, body, headers?.["idempotency-key"]);
      }
      const onRuns = RUN_ROUTE.exec(path);
      if (onRuns === null || method !== "GET") return undefined;
      const [, id] = onRuns;
      return id === undefined ? listRuns(query) : getRun(id, query);
    },
  };
}
