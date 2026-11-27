import { chargeVoid, integrityRun, integrityRunDetail, integrityRunPage, problem, studentCharge, studentChargePage, type ContractResponse } from "../../src/test/contract-responses.ts";
import { conceptId } from "./catalog-seed.ts";
import type { ModelResponse } from "./session-model.ts";
import { STATEMENT_STUDENTS } from "./statement-seed.ts";
import { studentId, type StudentRows } from "./students-seed.ts";

/**
 * The charges of a student, their void and the integrity check of the simulated API (charges-void-and-integrity cut): the five
 * operations below `/api/v1/charges` that the cut adds, over charges kept in integer cents for one scenario. It is a thin
 * simulation of the published shape and of only the rules a journey needs: the page of charges of a student (filtered by status and
 * by the first day of the period, ordered by one of the three fields, paged), the void (a reason of 20 to 500 characters, the
 * `Idempotency-Key` kept per request, the same key and body answering the same void, another key on a voided charge refused with 409),
 * and the nightly runs with their discrepancies. The statuses are derived from the voids kept here, the way the server derives them
 * from the void rows; the page never does. The real rules, and their limits, are tested in the backend.
 */
export const CHARGES_VOID_PATH_PATTERNS = {
  studentCharges: /^\/api\/v1\/charges\/students\/([^/]+)\/charges$/,
  void: /^\/api\/v1\/charges\/([^/]+)\/void$/,
  integrityRuns: /^\/api\/v1\/charges\/integrity-runs$/,
  latestRun: /^\/api\/v1\/charges\/integrity-runs\/latest$/,
  run: /^\/api\/v1\/charges\/integrity-runs\/([^/]+)$/,
} as const;

/** How many reads fail first in the `failing-first` behavior: the first try and the two retries of the page. */
export const FAILING_READS = 3;
/** A fictitious trace id, the shape the backend gives. */
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const REASON_MIN = 20;
const REASON_MAX = 500;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const SORT_FIELDS = ["periodStart", "dueOn", "total"] as const;

/** The student of the seeded scenario that has thirty charges, so the list has two pages of 25. */
export const MANY_CHARGES_STUDENT = STATEMENT_STUDENTS.manyLines;

export interface ChargeVoidOptions {
  /** The charges the scenario starts with: a few for some students, or none at all. */
  readonly seed: "seeded" | "empty";
  /** `forbidden`: the person lacks `charges:read`, so every read answers 403. */
  readonly access: "allowed" | "forbidden";
  /** `forbidden`: the person lacks `charges:void`. `already-voided`: someone else voided the charge first (409). `not-voidable`: the ledger refuses the reversal (409). */
  readonly writes: "allowed" | "forbidden" | "already-voided" | "not-voidable";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
  /** The nightly check: clean runs, a latest run with discrepancies, or it has not run yet. */
  readonly integrity: "clean" | "discrepancies" | "none";
}

export const DEFAULT_CHARGE_VOID: ChargeVoidOptions = { seed: "seeded", access: "allowed", writes: "allowed", reads: "ok", integrity: "clean" };

export interface ChargeVoidRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly body?: unknown;
  readonly headers: { readonly "idempotency-key": string | undefined };
}

/** What the simulator recorded of a void, for a test to assert on. */
export interface RecordedVoid {
  readonly chargeId: string;
  readonly reason: string;
  readonly idempotencyKey: string;
}

/** A charge in force with something left to pay, as the payments model needs it to allocate a payment. */
export interface PayableCharge {
  readonly id: string;
  readonly conceptCode: string;
  readonly conceptName: string;
  readonly periodLabel: string;
  readonly dueOn: string;
  readonly outstandingCents: number;
}

export interface ChargeVoidModel {
  /** The answer to a request of the cut, or `undefined` when the path is not one of its operations. */
  handle(request: ChargeVoidRequest): ModelResponse | undefined;
  /** The charges in force of a student with something left to pay, oldest due date first: what the payments allocate to (INV-17). */
  payable(studentId: string): readonly PayableCharge[];
  /** Records that a payment covered `cents` of a charge, so the list shows it as paid and a void of it is refused (409 `charge-has-payments`). */
  cover(chargeId: string, cents: number): void;
  /** The voids that took effect, in order. */
  voids(): readonly RecordedVoid[];
}

interface SeedCharge {
  readonly id: string;
  readonly studentId: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly dueOn: string;
  readonly baseCents: number;
  readonly taxCents: number;
  voidedAt?: string;
  voidReason?: string;
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });
const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse => wrap(problem(code, status, options));
const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

/** Whole units and two decimals of an amount in cents. */
const amountOf = (cents: number): { amount: string; currency: string } => ({
  amount: `${String(Math.floor(cents / 100))}.${String(cents % 100).padStart(2, "0")}`,
  currency: "HNL",
});

const chargeIdOf = (student: number, k: number): string => `00000000-0000-4000-8000-${String(200_000 + student * 100 + k).padStart(12, "0")}`;
const runIdOf = (n: number): string => `00000000-0000-4000-8000-${String(300_000 + n).padStart(12, "0")}`;

/** A month `offset` months after September 2026, as the first and last day and the due day. */
function monthOf(offset: number): { periodStart: string; periodEnd: string; dueOn: string } {
  const index = 8 + offset;
  const year = 2026 + Math.floor(index / 12);
  const month = (index % 12) + 1;
  const two = (n: number) => String(n).padStart(2, "0");
  const last = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return { periodStart: `${String(year)}-${two(month)}-01`, periodEnd: `${String(year)}-${two(month)}-${two(last)}`, dueOn: `${String(year)}-${two(month)}-05` };
}

function seededCharges(): SeedCharge[] {
  const counts = new Map<number, number>([
    [STATEMENT_STUDENTS.twelveMonths, 3],
    [STATEMENT_STUDENTS.credit, 3],
    [STATEMENT_STUDENTS.twoCurrencies, 2],
    [MANY_CHARGES_STUDENT, 30],
  ]);
  const charges: SeedCharge[] = [];
  for (const [student, count] of counts) {
    for (let k = 0; k < count; k += 1) {
      // The base moves in steps of 50.00, so the tax (15 percent) is whole cents; the simulator writes both, the page never derives one from the other.
      const baseCents = 100_000 + (k % 3) * 5_000;
      charges.push({ id: chargeIdOf(student, k), studentId: studentId(student), ...monthOf(k), baseCents, taxCents: (baseCents * 15) / 100 });
    }
  }
  return charges;
}

export function createChargeVoidModel(options: ChargeVoidOptions, dependencies: { readonly students: { rows(): Readonly<StudentRows> } }, now: () => Date = () => new Date()): ChargeVoidModel {
  const charges = options.seed === "seeded" ? seededCharges() : [];
  const done = new Map<string, { readonly fingerprint: string; readonly response: ContractResponse }>();
  const recorded: RecordedVoid[] = [];
  /** What the payments covered of each charge, in cents. The simulated server keeps it, the way the real one derives it from the allocations. */
  const covered = new Map<string, number>();
  let reads = 0;
  const clean = (n: number) => integrityRun({ id: runIdOf(n), startedAt: `2026-11-${String(18 - n).padStart(2, "0")}T08:30:00Z`, finishedAt: `2026-11-${String(18 - n).padStart(2, "0")}T08:30:41Z` });
  const dirty = integrityRun({ id: runIdOf(0), status: "DISCREPANCIES", discrepancyCount: 2, startedAt: "2026-11-18T08:30:00Z", finishedAt: "2026-11-18T08:30:41Z" });
  const findings = [
    {
      kind: "CHARGE_POSTING_MISSING" as const,
      chargeId: chargeIdOf(1, 0),
      expected: amountOf(115_000),
      actual: amountOf(0),
    },
    { kind: "RECEIVABLE_MISMATCH" as const, expected: amountOf(345_000), actual: amountOf(230_000) },
  ];
  const runs = options.integrity === "none" ? [] : options.integrity === "clean" ? [clean(0), clean(1), clean(2)] : [dirty, clean(1), clean(2)];

  function readFailure(): ModelResponse | undefined {
    if (options.access === "forbidden") return failure("forbidden", 403);
    if (options.reads !== "failing-first") return undefined;
    reads += 1;
    return reads <= FAILING_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  }

  const statusOf = (charge: SeedCharge): "ACTIVE" | "VOIDED" => (charge.voidedAt === undefined ? "ACTIVE" : "VOIDED");

  /** What is still to pay of a charge in force: its total less what the payments covered. */
  const owedCents = (charge: SeedCharge): number => charge.baseCents + charge.taxCents - (covered.get(charge.id) ?? 0);

  function rowOf(charge: SeedCharge) {
    const totalCents = charge.baseCents + charge.taxCents;
    return studentCharge({
      id: charge.id,
      paymentConceptId: conceptId(1),
      periodStart: charge.periodStart,
      periodEnd: charge.periodEnd,
      periodLabel: charge.periodStart.slice(0, 7),
      dueOn: charge.dueOn,
      base: amountOf(charge.baseCents),
      benefitAmount: amountOf(0),
      benefits: [],
      netBase: amountOf(charge.baseCents),
      tax: amountOf(charge.taxCents),
      total: amountOf(totalCents),
      paid: amountOf(covered.get(charge.id) ?? 0),
      outstanding: amountOf(charge.voidedAt === undefined ? totalCents - (covered.get(charge.id) ?? 0) : 0),
      status: statusOf(charge),
      settled: charge.voidedAt === undefined && owedCents(charge) === 0,
      ...(charge.voidedAt === undefined
        ? {}
        : { voided: { reason: charge.voidReason ?? "", reversalTransactionId: runIdOf(900), voidId: runIdOf(901), voidedAt: charge.voidedAt } }),
    });
  }

  function listCharges(id: string, query: URLSearchParams): ModelResponse {
    const failed = readFailure();
    if (failed !== undefined) return failed;
    if (!dependencies.students.rows().students.some((row) => row.id === id)) return failure("resource-not-found", 404);

    const errors: { field: string; reason: string }[] = [];
    const status = query.get("status");
    if (status !== null && status !== "ACTIVE" && status !== "VOIDED") errors.push({ field: "status", reason: "pattern" });
    const from = query.get("periodFrom");
    const to = query.get("periodTo");
    if (from !== null && !DAY.test(from)) errors.push({ field: "periodFrom", reason: "invalid" });
    if (to !== null && !DAY.test(to)) errors.push({ field: "periodTo", reason: "invalid" });
    const outstanding = query.get("outstanding");
    if (outstanding !== null && outstanding !== "true" && outstanding !== "false") errors.push({ field: "outstanding", reason: "invalid" });
    const onlyOwed = outstanding === "true";
    const sort = query.get("sort");
    // With outstanding=true and no sort the order is the one in which a payment is allocated: due date, period, concept and identifier.
    const [field = "periodStart", direction = "asc"] = (sort ?? (onlyOwed ? "dueOn,asc" : "periodStart,desc")).split(",");
    if (!(SORT_FIELDS as readonly string[]).includes(field) || (direction !== "asc" && direction !== "desc")) errors.push({ field: "sort", reason: "invalid" });
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) errors.push({ field: "page", reason: "invalid" });
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) errors.push({ field: "size", reason: "invalid" });
    if (errors.length > 0) return invalid(errors);

    const matching = charges
      .filter((charge) => charge.studentId === id)
      .filter((charge) => status === null || statusOf(charge) === status)
      .filter((charge) => !onlyOwed || (charge.voidedAt === undefined && owedCents(charge) > 0))
      .filter((charge) => from === null || charge.periodStart >= from)
      .filter((charge) => to === null || charge.periodStart <= to);
    const key = (charge: SeedCharge): string | number => (field === "total" ? charge.baseCents + charge.taxCents : field === "dueOn" ? charge.dueOn : charge.periodStart);
    const ordered = [...matching].sort((a, b) => {
      const left = key(a);
      const right = key(b);
      const order = left < right ? -1 : left > right ? 1 : 0;
      if (order !== 0) return direction === "desc" ? -order : order;
      return a.dueOn.localeCompare(b.dueOn) || a.periodStart.localeCompare(b.periodStart) || a.id.localeCompare(b.id);
    });
    return wrap(studentChargePage(ordered.slice(page * size, (page + 1) * size).map(rowOf), { page, size, total: ordered.length }));
  }

  function reasonProblem(reason: unknown): { field: string; reason: string } | undefined {
    if (typeof reason !== "string") return { field: "reason", reason: "required" };
    const stripped = reason.trim();
    const length = Array.from(stripped).length;
    if (length < REASON_MIN || length > REASON_MAX) return { field: "reason", reason: "size" };
    if (/[\p{Cc}\p{Cf}]/u.test(stripped)) return { field: "reason", reason: "pattern" };
    return undefined;
  }

  function voidCharge(id: string, request: ChargeVoidRequest): ModelResponse {
    if (options.writes === "forbidden") return failure("forbidden", 403);
    const key = request.headers["idempotency-key"];
    if (key === undefined || key === "") return failure("idempotency-key-missing", 400);
    const body: unknown = request.body;
    const reason: unknown = typeof body === "object" && body !== null ? (body as { reason?: unknown }).reason : undefined;
    const bad = reasonProblem(reason);
    if (bad !== undefined) return invalid([bad]);
    const text = (reason as string).trim();
    const charge = charges.find((row) => row.id === id);
    if (charge === undefined) return failure("resource-not-found", 404);
    if ((covered.get(id) ?? 0) > 0) return failure("charge-has-payments", 409);

    const fingerprint = `${id}\n${text}`;
    const previous = done.get(key);
    if (previous !== undefined) return previous.fingerprint === fingerprint ? wrap(previous.response) : failure("idempotency-payload-mismatch", 422);
    if (options.writes === "not-voidable") return failure("charge-not-voidable", 409);
    if (options.writes === "already-voided" && charge.voidedAt === undefined) {
      // Someone else voided it a moment ago: from now on the charge reads as voided, and this try is refused.
      charge.voidedAt = now().toISOString();
      charge.voidReason = "Anulado por otra persona";
    }
    if (charge.voidedAt !== undefined) return failure("charge-already-voided", 409);

    charge.voidedAt = now().toISOString();
    charge.voidReason = text;
    const response = chargeVoid({ chargeId: id, reason: text, voidedAt: charge.voidedAt });
    done.set(key, { fingerprint, response });
    recorded.push({ chargeId: id, reason: text, idempotencyKey: key });
    return wrap(response);
  }

  function pageOfRuns(query: URLSearchParams): ModelResponse {
    const failed = readFailure();
    if (failed !== undefined) return failed;
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > MAX_SIZE) return invalid([{ field: "page", reason: "invalid" }]);
    return wrap(integrityRunPage(runs.slice(page * size, (page + 1) * size), { page, size, total: runs.length }));
  }

  const detailOf = (run: (typeof runs)[number]): ModelResponse => wrap(integrityRunDetail(run, run.status === "DISCREPANCIES" ? findings : []));

  return {
    handle(request) {
      const { method, path } = request;
      const patterns = CHARGES_VOID_PATH_PATTERNS;
      if (method === "GET" && patterns.integrityRuns.test(path)) return pageOfRuns(request.query);
      if (method === "GET" && patterns.latestRun.test(path)) {
        const failed = readFailure();
        if (failed !== undefined) return failed;
        const latest = runs[0];
        return latest === undefined ? failure("resource-not-found", 404) : detailOf(latest);
      }
      const run = patterns.run.exec(path);
      if (method === "GET" && run !== null) {
        const failed = readFailure();
        if (failed !== undefined) return failed;
        const found = runs.find((row) => row.id === decodeURIComponent(run[1] ?? ""));
        return found === undefined ? failure("resource-not-found", 404) : detailOf(found);
      }
      const list = patterns.studentCharges.exec(path);
      if (method === "GET" && list !== null) return listCharges(decodeURIComponent(list[1] ?? ""), request.query);
      const voiding = patterns.void.exec(path);
      if (method === "POST" && voiding !== null) return voidCharge(decodeURIComponent(voiding[1] ?? ""), request);
      return undefined;
    },
    voids: () => recorded,
    payable: (studentId) =>
      charges
        .filter((charge) => charge.studentId === studentId && charge.voidedAt === undefined)
        .map((charge) => ({
          id: charge.id,
          conceptCode: "COL",
          conceptName: "Colegiatura",
          periodLabel: charge.periodStart.slice(0, 7),
          dueOn: charge.dueOn,
          outstandingCents: charge.baseCents + charge.taxCents - (covered.get(charge.id) ?? 0),
        }))
        .filter((charge) => charge.outstandingCents > 0)
        .sort((a, b) => a.dueOn.localeCompare(b.dueOn) || a.periodLabel.localeCompare(b.periodLabel) || a.id.localeCompare(b.id)),
    cover: (chargeId, cents) => {
      covered.set(chargeId, (covered.get(chargeId) ?? 0) + cents);
    },
  };
}
