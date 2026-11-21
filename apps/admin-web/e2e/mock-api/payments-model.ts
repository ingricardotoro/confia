import { paymentBody, paymentPage, paymentPostingAccount, paymentPostingAccountMap, paymentSummary, problem, type ContractResponse } from "../../src/test/contract-responses.ts";
import type { CashboxModel } from "./cashbox-model.ts";
import type { ChargeVoidModel } from "./charge-void-model.ts";
import { ACCOUNT_IDS } from "./ledger-seed.ts";
import type { ModelResponse } from "./session-model.ts";
import type { StatementModel } from "./statement-model.ts";
import { studentId, type StudentRows } from "./students-seed.ts";

/**
 * The payments of the simulated API (payments-register cut): the operations below `/api/v1/payments`, over payments kept in integer cents for one
 * scenario. It is a thin simulation of the published shape and of only the rules a journey needs: cash is confirmed at once, allocated to the charges in
 * force oldest due date first (INV-17) with the remainder left in favor (INV-18), and needs an open cashbox of the person (409 `cash-session-not-open`,
 * INV-20); a transfer is declared and changes nothing until another person confirms it (403 `self-approval-forbidden` for the one who registered it) or
 * rejects it with a reason of 20 to 500 characters; a bank and a reference are never repeated among the payments not rejected (409
 * `duplicate-transfer-reference`); the cash and bank accounts must be set (409 `posting-accounts-incomplete`); the \`Idempotency-Key\` is kept per
 * request (the same key and body answer the same, another body is 422); the list pages, filters and orders. The allocation and every figure is the
 * SIMULATED SERVER's, in cents: the page under test does none. The real rules, and their limits, are tested in the backend.
 */
export const PAYMENTS_BASE = "/api/v1/payments";

export const PAYMENT_PATH_PATTERNS = {
  payments: /^\/api\/v1\/payments$/,
  accounts: /^\/api\/v1\/payments\/posting-accounts$/,
  payment: /^\/api\/v1\/payments\/([^/]+)$/,
  confirm: /^\/api\/v1\/payments\/([^/]+)\/confirm$/,
  reject: /^\/api\/v1\/payments\/([^/]+)\/reject$/,
} as const;

/** How many reads fail first in the `failing-first` behavior: the first try and the two retries of the page. */
export const FAILING_READS = 3;
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const REASON_MIN = 20;
const REASON_MAX = 500;
const REFERENCE_MIN = 4;
const REFERENCE_MAX = 40;
const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const SORT_FIELDS = ["registeredAt", "receivedOn", "amount"] as const;
const STATUSES = ["declared", "confirmed", "rejected"] as const;
const METHODS = ["CASH", "BANK_TRANSFER"] as const;

/** The person who signs in with the simulator: who registers every payment of the journeys. */
export const ME = "00000000-0000-4000-8000-0000000000a1";
/** Another person, who registered the declared transfer the seed holds and who may be asked to confirm. */
const OTHER = "00000000-0000-4000-8000-0000000000c9";
export const paymentId = (n: number): string => `00000000-0000-4000-8000-${String(600_000 + n).padStart(12, "0")}`;

export interface PaymentsOptions {
  /** `none`: no payment at all. `mixed`: a transfer declared by another person, one declared by the person, and a confirmed cash payment. */
  readonly seed: "none" | "mixed";
  /** `forbidden`: the person lacks the permission, so every operation answers 403. */
  readonly access: "allowed" | "forbidden";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
  /** `incomplete`: the cash and bank accounts are not set, so cash and a confirmation are refused (409). */
  readonly accounts: "complete" | "incomplete";
}

export const DEFAULT_PAYMENTS: PaymentsOptions = { seed: "mixed", access: "allowed", reads: "ok", accounts: "complete" };

export interface PaymentsRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly body?: unknown;
  readonly headers: { readonly "idempotency-key": string | undefined };
}

/** What the simulator recorded of a write, for a test to assert on. */
export interface RecordedPaymentWrite {
  readonly operation: "register" | "confirm" | "reject";
  readonly paymentId: string | undefined;
  readonly idempotencyKey: string;
}

export interface PaymentsModel {
  /** The answer to a request of the cut, or \`undefined\` when the path is not one of its operations. */
  handle(request: PaymentsRequest): ModelResponse | undefined;
  /** The writes that took effect, in order. */
  writes(): readonly RecordedPaymentWrite[];
}

interface Allocation {
  readonly chargeId: string;
  readonly conceptCode: string;
  readonly conceptName: string;
  readonly periodLabel: string;
  readonly dueOn: string;
  readonly cents: number;
}

interface Payment {
  readonly id: string;
  readonly studentId: string;
  readonly method: (typeof METHODS)[number];
  status: (typeof STATUSES)[number];
  readonly cents: number;
  readonly receivedOn: string;
  readonly registeredAt: string;
  readonly registeredBy: string;
  readonly reference?: string;
  readonly bankName?: string;
  readonly notes?: string;
  allocations: Allocation[];
  unallocatedCents: number;
  decision?: { readonly kind: "confirmed" | "rejected"; readonly decidedAt: string; readonly reason?: string };
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });
const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse => wrap(problem(code, status, options));
const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

const amountOf = (cents: number): { amount: string; currency: string } => ({
  amount: `${String(Math.floor(cents / 100))}.${String(cents % 100).padStart(2, "0")}`,
  currency: "HNL",
});
const centsOf = (amount: string): number => Math.round(Number(amount) * 100);

function seeded(option: PaymentsOptions["seed"]): Payment[] {
  if (option === "none") return [];
  const student = studentId(1);
  return [
    {
      id: paymentId(1),
      studentId: student,
      method: "BANK_TRANSFER",
      status: "declared",
      cents: 126_500,
      receivedOn: "2026-11-18",
      registeredAt: "2026-11-18T15:00:00Z",
      registeredBy: OTHER,
      reference: "BA-001234",
      bankName: "Banco Atlántida",
      allocations: [],
      unallocatedCents: 0,
    },
    {
      id: paymentId(2),
      studentId: student,
      method: "BANK_TRANSFER",
      status: "declared",
      cents: 50_000,
      receivedOn: "2026-11-19",
      registeredAt: "2026-11-19T10:00:00Z",
      registeredBy: ME,
      reference: "BA-009876",
      bankName: "Banco Atlántida",
      allocations: [],
      unallocatedCents: 0,
    },
    {
      id: paymentId(3),
      studentId: studentId(2),
      method: "CASH",
      status: "confirmed",
      cents: 80_000,
      receivedOn: "2026-11-19",
      registeredAt: "2026-11-19T09:00:00Z",
      registeredBy: ME,
      allocations: [],
      unallocatedCents: 80_000,
    },
  ];
}

/** Whether a free text meets the bound of the server: 20 to 500 characters, once stripped, with no control or format character. */
function reasonProblem(reason: unknown): { field: string; reason: string } | undefined {
  if (typeof reason !== "string") return { field: "reason", reason: "required" };
  const stripped = reason.trim();
  const length = Array.from(stripped).length;
  if (length < REASON_MIN || length > REASON_MAX) return { field: "reason", reason: "size" };
  return /[\p{Cc}\p{Cf}]/u.test(stripped) ? { field: "reason", reason: "pattern" } : undefined;
}

export function createPaymentsModel(
  options: PaymentsOptions,
  dependencies: {
    readonly students: { rows(): Readonly<StudentRows> };
    readonly charges: ChargeVoidModel;
    readonly statement: StatementModel;
    readonly cashbox: CashboxModel;
  },
  now: () => Date = () => new Date(),
): PaymentsModel {
  const payments = seeded(options.seed);
  const done = new Map<string, { readonly fingerprint: string; readonly response: ContractResponse }>();
  const recorded: RecordedPaymentWrite[] = [];
  let accountsSet = options.accounts === "complete";
  let accountVersion = 0;
  let reads = 0;
  let counter = 100;

  function readFailure(): ModelResponse | undefined {
    if (options.access === "forbidden") return failure("forbidden", 403);
    if (options.reads !== "failing-first") return undefined;
    reads += 1;
    return reads <= FAILING_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  }

  const studentRow = (id: string) => dependencies.students.rows().students.find((row) => row.id === id);
  const codeOf = (id: string): string | undefined => studentRow(id)?.studentCode;

  const summaryOf = (payment: Payment) =>
    paymentSummary({
      id: payment.id,
      studentId: payment.studentId,
      ...(codeOf(payment.studentId) === undefined ? {} : { studentCode: codeOf(payment.studentId) as string }),
      method: payment.method,
      status: payment.status,
      amount: amountOf(payment.cents),
      receivedOn: payment.receivedOn,
      registeredAt: payment.registeredAt,
      registeredBy: payment.registeredBy,
      ...(payment.reference === undefined ? {} : { reference: payment.reference }),
      ...(payment.bankName === undefined ? {} : { bankName: payment.bankName }),
    });

  const bodyOf = (payment: Payment) => {
    const allocatedCents = payment.allocations.reduce((sum, allocation) => sum + allocation.cents, 0);
    return paymentBody({
      ...summaryOf(payment),
      ...(payment.notes === undefined ? {} : { notes: payment.notes }),
      allocated: amountOf(allocatedCents),
      unallocated: amountOf(payment.unallocatedCents),
      allocations: payment.allocations.map((allocation) => ({
        chargeId: allocation.chargeId,
        conceptCode: allocation.conceptCode,
        conceptName: allocation.conceptName,
        periodLabel: allocation.periodLabel,
        dueOn: allocation.dueOn,
        amount: amountOf(allocation.cents),
        allocatedAt: payment.decision?.decidedAt ?? payment.registeredAt,
      })),
      ...(payment.decision === undefined
        ? {}
        : {
            decision: {
              id: paymentId(900 + counter),
              kind: payment.decision.kind,
              decidedAt: payment.decision.decidedAt,
              decidedBy: payment.registeredBy === ME ? OTHER : ME,
              ...(payment.decision.reason === undefined ? {} : { reason: payment.decision.reason }),
            },
          }),
    });
  };

  const answerOf = (payment: Payment, status = 200): ModelResponse => wrap({ status, body: bodyOf(payment), contentType: "application/json" });

  /** Allocates the payment to the charges in force, oldest due date first, never over what a charge still owes; what is left stays in favor. */
  function post(payment: Payment, effectiveOn: string): void {
    let left = payment.cents;
    const allocations: Allocation[] = [];
    for (const charge of dependencies.charges.payable(payment.studentId)) {
      if (left <= 0) break;
      const take = Math.min(left, charge.outstandingCents);
      allocations.push({ chargeId: charge.id, conceptCode: charge.conceptCode, conceptName: charge.conceptName, periodLabel: charge.periodLabel, dueOn: charge.dueOn, cents: take });
      dependencies.charges.cover(charge.id, take);
      left -= take;
    }
    payment.allocations = allocations;
    payment.unallocatedCents = left;
    dependencies.statement.postPayment(payment.studentId, payment.cents, effectiveOn, paymentId(700 + payments.length + counter));
  }

  function list(query: URLSearchParams): ModelResponse {
    const failed = readFailure();
    if (failed !== undefined) return failed;
    const errors: { field: string; reason: string }[] = [];
    const status = query.get("status");
    if (status !== null && !(STATUSES as readonly string[]).includes(status)) errors.push({ field: "status", reason: "pattern" });
    const method = query.get("method");
    if (method !== null && !(METHODS as readonly string[]).includes(method)) errors.push({ field: "method", reason: "pattern" });
    const from = query.get("from");
    const to = query.get("to");
    if (from !== null && !DAY.test(from)) errors.push({ field: "from", reason: "invalid" });
    if (to !== null && !DAY.test(to)) errors.push({ field: "to", reason: "invalid" });
    const [field = "registeredAt", direction = "desc"] = (query.get("sort") ?? "registeredAt,desc").split(",");
    if (!(SORT_FIELDS as readonly string[]).includes(field) || (direction !== "asc" && direction !== "desc")) errors.push({ field: "sort", reason: "invalid" });
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) errors.push({ field: "page", reason: "invalid" });
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) errors.push({ field: "size", reason: "invalid" });
    if (errors.length > 0) return invalid(errors);

    const matching = payments
      .filter((payment) => status === null || payment.status === status)
      .filter((payment) => method === null || payment.method === method)
      .filter((payment) => from === null || payment.receivedOn >= from)
      .filter((payment) => to === null || payment.receivedOn <= to);
    const key = (payment: Payment): string | number => (field === "amount" ? payment.cents : field === "receivedOn" ? payment.receivedOn : payment.registeredAt);
    const ordered = [...matching].sort((a, b) => {
      const left = key(a);
      const right = key(b);
      const order = left < right ? -1 : left > right ? 1 : 0;
      return direction === "desc" ? -order : order;
    });
    return wrap(paymentPage(ordered.slice(page * size, (page + 1) * size).map(summaryOf), { page, size, total: ordered.length }));
  }

  function register(request: PaymentsRequest): ModelResponse {
    if (options.access === "forbidden") return failure("forbidden", 403);
    const key = request.headers["idempotency-key"];
    if (key === undefined || key === "") return failure("idempotency-key-missing", 400);
    const body = (typeof request.body === "object" && request.body !== null ? request.body : {}) as Record<string, unknown>;
    const errors: { field: string; reason: string }[] = [];
    const amount = (typeof body["amount"] === "object" && body["amount"] !== null ? body["amount"] : {}) as { amount?: unknown; currency?: unknown };
    if (typeof amount.amount !== "string") errors.push({ field: "amount.amount", reason: "required" });
    else if (!AMOUNT.test(amount.amount)) errors.push({ field: "amount.amount", reason: "pattern" });
    else if (centsOf(amount.amount) <= 0) errors.push({ field: "amount.amount", reason: "positive" });
    if (amount.currency !== "HNL") errors.push({ field: "amount.currency", reason: "pattern" });
    const method = body["method"];
    if (method !== "CASH" && method !== "BANK_TRANSFER") errors.push({ field: "method", reason: "pattern" });
    const receivedOn = body["receivedOn"];
    // Today is the day of the institution (America/Tegucigalpa), not the one of UTC.
    const today = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Tegucigalpa", year: "numeric", month: "2-digit", day: "2-digit" }).format(now());
    if (typeof receivedOn !== "string" || !DAY.test(receivedOn) || receivedOn > today) errors.push({ field: "receivedOn", reason: "invalid" });
    const reference = typeof body["reference"] === "string" ? body["reference"].trim() : undefined;
    const bankName = typeof body["bankName"] === "string" ? body["bankName"].trim() : undefined;
    if (method === "BANK_TRANSFER") {
      if (reference === undefined || reference === "") errors.push({ field: "reference", reason: "not-blank" });
      else if (Array.from(reference).length < REFERENCE_MIN || Array.from(reference).length > REFERENCE_MAX) errors.push({ field: "reference", reason: "size" });
      if (bankName === undefined || bankName === "") errors.push({ field: "bankName", reason: "not-blank" });
    } else if (reference !== undefined || bankName !== undefined) {
      errors.push({ field: reference === undefined ? "bankName" : "reference", reason: "not-allowed" });
    }
    const student = typeof body["studentId"] === "string" ? studentRow(body["studentId"]) : undefined;
    if (typeof body["studentId"] !== "string") errors.push({ field: "studentId", reason: "required" });
    if (method === "CASH" && typeof receivedOn === "string" && DAY.test(receivedOn) && receivedOn <= today && receivedOn !== today) {
      errors.push({ field: "receivedOn", reason: "cash-received-today" });
    }
    if (errors.length > 0) return invalid(errors);
    if (student === undefined) return failure("resource-not-found", 404);

    const fingerprint = JSON.stringify(["register", ME, body]);
    const previous = done.get(key);
    if (previous !== undefined) return previous.fingerprint === fingerprint ? wrap(previous.response) : failure("idempotency-payload-mismatch", 422);

    if (method === "CASH") {
      if (!accountsSet) return failure("posting-accounts-incomplete", 409);
      if (!dependencies.cashbox.hasOpenSession()) return failure("cash-session-not-open", 409);
    } else if (payments.some((payment) => payment.status !== "rejected" && payment.method === "BANK_TRANSFER" && payment.bankName?.toLowerCase() === bankName?.toLowerCase() && payment.reference?.toLowerCase() === reference?.toLowerCase())) {
      return failure("duplicate-transfer-reference", 409);
    }

    counter += 1;
    const payment: Payment = {
      id: paymentId(counter),
      studentId: student.id,
      method: method as Payment["method"],
      status: method === "CASH" ? "confirmed" : "declared",
      cents: centsOf(amount.amount as string),
      receivedOn: receivedOn as string,
      registeredAt: now().toISOString(),
      registeredBy: ME,
      ...(method === "BANK_TRANSFER" ? { reference: reference as string, bankName: bankName as string } : {}),
      ...(typeof body["notes"] === "string" && body["notes"] !== "" ? { notes: body["notes"] } : {}),
      allocations: [],
      unallocatedCents: 0,
    };
    if (method === "CASH") {
      post(payment, payment.receivedOn);
      dependencies.cashbox.recordPaymentIn(payment.cents);
    }
    payments.push(payment);
    const response = { status: 201, body: bodyOf(payment), contentType: "application/json" };
    done.set(key, { fingerprint, response });
    recorded.push({ operation: "register", paymentId: payment.id, idempotencyKey: key });
    return wrap(response);
  }

  function decide(id: string, kind: "confirm" | "reject", request: PaymentsRequest): ModelResponse {
    if (options.access === "forbidden") return failure("forbidden", 403);
    const key = request.headers["idempotency-key"];
    if (key === undefined || key === "") return failure("idempotency-key-missing", 400);
    const body: unknown = request.body;
    const reason: unknown = typeof body === "object" && body !== null ? (body as { reason?: unknown }).reason : undefined;
    if (kind === "reject") {
      const bad = reasonProblem(reason);
      if (bad !== undefined) return invalid([bad]);
    }
    const payment = payments.find((row) => row.id === id);
    if (payment === undefined) return failure("resource-not-found", 404);

    const fingerprint = JSON.stringify([kind, ME, id, kind === "reject" ? (reason as string).trim() : ""]);
    const previous = done.get(key);
    if (previous !== undefined) return previous.fingerprint === fingerprint ? wrap(previous.response) : failure("idempotency-payload-mismatch", 422);
    if (payment.status !== "declared") return failure("payment-not-declared", 409);
    if (payment.registeredBy === ME) return failure("self-approval-forbidden", 403);
    if (kind === "confirm") {
      if (!accountsSet) return failure("posting-accounts-incomplete", 409);
      payment.status = "confirmed";
      payment.decision = { kind: "confirmed", decidedAt: now().toISOString() };
      post(payment, now().toISOString().slice(0, 10));
    } else {
      payment.status = "rejected";
      payment.decision = { kind: "rejected", decidedAt: now().toISOString(), reason: (reason as string).trim() };
    }
    const response = { status: 200, body: bodyOf(payment), contentType: "application/json" };
    done.set(key, { fingerprint, response });
    recorded.push({ operation: kind, paymentId: id, idempotencyKey: key });
    return wrap(response);
  }

  const accountRows = () =>
    accountsSet
      ? [
          paymentPostingAccount({ id: paymentId(801), purpose: "CASH", ledgerAccountId: ACCOUNT_IDS.cash, version: accountVersion }),
          paymentPostingAccount({ id: paymentId(802), purpose: "BANK", ledgerAccountId: ACCOUNT_IDS.cash, version: accountVersion }),
        ]
      : [];

  return {
    handle(request) {
      const { method, path } = request;
      const patterns = PAYMENT_PATH_PATTERNS;
      if (patterns.accounts.test(path)) {
        if (method === "GET") return readFailure() ?? wrap(paymentPostingAccountMap(accountRows()));
        if (method === "PUT") {
          if (options.access === "forbidden") return failure("forbidden", 403);
          accountsSet = true;
          accountVersion += 1;
          return wrap(paymentPostingAccountMap(accountRows()));
        }
        return undefined;
      }
      if (method === "GET" && patterns.payments.test(path)) return list(request.query);
      if (method === "POST" && patterns.payments.test(path)) return register(request);
      const confirming = patterns.confirm.exec(path);
      if (method === "POST" && confirming !== null) return decide(decodeURIComponent(confirming[1] ?? ""), "confirm", request);
      const rejecting = patterns.reject.exec(path);
      if (method === "POST" && rejecting !== null) return decide(decodeURIComponent(rejecting[1] ?? ""), "reject", request);
      const detail = patterns.payment.exec(path);
      if (method === "GET" && detail !== null) {
        const failed = readFailure();
        if (failed !== undefined) return failed;
        const found = payments.find((row) => row.id === decodeURIComponent(detail[1] ?? ""));
        return found === undefined ? failure("resource-not-found", 404) : answerOf(found);
      }
      return undefined;
    },
    writes: () => recorded,
  };
}
