import { cashMovement, cashSession, cashSessionPage, cashSessionRow, problem, recordedMovement, type ContractResponse } from "../../src/test/contract-responses.ts";
import type { ModelResponse } from "./session-model.ts";

/**
 * The cashbox of the simulated API (cashbox-sessions cut): the seven operations below `/api/v1/cashbox`, over sessions kept in integer cents
 * for one scenario. It is a thin simulation of the published shape and of only the rules a journey needs: one open session per cashier (409),
 * movements only on an open session of the person (a withdrawal the expected cash cannot cover is refused), the close that computes the
 * difference and demands its reason of 20 to 500 characters (400 `cash-difference-reason-required`), the approval that refuses the cashier of
 * the session (403), the `Idempotency-Key` kept per request (the same key and body answer the same, another body is 422), and the list that
 * pages, filters and orders. The arithmetic is the SIMULATED SERVER's, in cents: the page under test does none. The real rules, and their limits,
 * are tested in the backend.
 */
export const CASHBOX_BASE = "/api/v1/cashbox";

export const CASHBOX_PATH_PATTERNS = {
  sessions: /^\/api\/v1\/cashbox\/sessions$/,
  current: /^\/api\/v1\/cashbox\/sessions\/current$/,
  session: /^\/api\/v1\/cashbox\/sessions\/([^/]+)$/,
  movements: /^\/api\/v1\/cashbox\/sessions\/([^/]+)\/movements$/,
  close: /^\/api\/v1\/cashbox\/sessions\/([^/]+)\/close$/,
  approve: /^\/api\/v1\/cashbox\/sessions\/([^/]+)\/approve$/,
} as const;

/** How many reads fail first in the `failing-first` behavior: the first try and the two retries of the page. */
export const FAILING_READS = 3;
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 200;
const TEXT_MIN = 20;
const TEXT_MAX = 500;
const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const STATUSES = ["open", "pending_approval", "closed"] as const;
const TYPES = ["deposit", "withdrawal", "adjustment"] as const;

/** The person who signs in with the simulator: the cashier of every session they open. */
export const ME = "00000000-0000-4000-8000-0000000000a1";
/** Another cashier, whose sessions the seeded list holds. */
const OTHER = "00000000-0000-4000-8000-0000000000c9";
export const cashSessionId = (n: number): string => `00000000-0000-4000-8000-${String(400_000 + n).padStart(12, "0")}`;

export interface CashboxOptions {
  /** `none`: no session at all. `mixed`: a session of another cashier that awaits its approval, and a closed one. `mine-open`: the same, and the person already has an open session with two movements. */
  readonly seed: "none" | "mixed" | "mine-open";
  /** `forbidden`: the person lacks the permission, so every operation answers 403. */
  readonly access: "allowed" | "forbidden";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_CASHBOX: CashboxOptions = { seed: "mixed", access: "allowed", reads: "ok" };

export interface CashboxRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly body?: unknown;
  readonly headers: { readonly "idempotency-key": string | undefined };
}

/** What the simulator recorded of a write, for a test to assert on. */
export interface RecordedCashWrite {
  readonly operation: "open" | "movement" | "close" | "approve";
  readonly sessionId: string | undefined;
  readonly idempotencyKey: string;
}

export interface CashboxModel {
  /** The answer to a request of the cut, or `undefined` when the path is not one of its operations. */
  handle(request: CashboxRequest): ModelResponse | undefined;
  /** The writes that took effect, in order. */
  writes(): readonly RecordedCashWrite[];
  /** Whether the person has an open session, which a cash payment needs (INV-20). */
  hasOpenSession(): boolean;
  /** Writes the `payment_in` movement of a cash payment into the open session of the person, so its expected cash rises; `false` when there is none. */
  recordPaymentIn(cents: number): boolean;
}

interface Movement {
  readonly id: string;
  readonly type: (typeof TYPES)[number] | "payment_in";
  readonly cents: number;
  readonly notes?: string;
  readonly createdAt: string;
}

interface Session {
  readonly id: string;
  readonly cashier: string;
  status: (typeof STATUSES)[number];
  readonly openedAt: string;
  readonly floatCents: number;
  readonly movements: Movement[];
  close?: { closedAt: string; declaredCents: number; expectedCents: number; reason?: string };
  approval?: { approvedAt: string; note: string };
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });
const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse => wrap(problem(code, status, options));
const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

const amountOf = (cents: number): { amount: string; currency: string } => {
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(cents);
  return { amount: `${sign}${String(Math.floor(abs / 100))}.${String(abs % 100).padStart(2, "0")}`, currency: "HNL" };
};
const centsOf = (amount: string): number => Math.round(Number(amount) * 100);

const expectedOf = (session: Session): number =>
  session.movements.reduce((sum, movement) => sum + (movement.type === "withdrawal" ? -movement.cents : movement.cents), session.floatCents);

/** Whether a free text meets the bound of the server: 20 to 500 characters, once stripped, with no control or format character. */
function textProblem(text: unknown): "size" | "pattern" | undefined {
  if (typeof text !== "string") return "size";
  const stripped = text.trim();
  const length = Array.from(stripped).length;
  if (length < TEXT_MIN || length > TEXT_MAX) return "size";
  return /[\p{Cc}\p{Cf}]/u.test(stripped) ? "pattern" : undefined;
}

function seeded(option: CashboxOptions["seed"]): Session[] {
  if (option === "none") return [];
  const others: Session[] = [
    {
      id: cashSessionId(2),
      cashier: OTHER,
      status: "pending_approval",
      openedAt: "2026-11-18T08:00:00Z",
      floatCents: 50_000,
      movements: [
        { id: cashSessionId(21), type: "deposit", cents: 10_000, createdAt: "2026-11-18T09:00:00Z" },
        { id: cashSessionId(22), type: "withdrawal", cents: 5_000, notes: "Entrega a tesorería", createdAt: "2026-11-18T12:00:00Z" },
      ],
      close: { closedAt: "2026-11-18T17:00:00Z", declaredCents: 54_000, expectedCents: 55_000, reason: "Billete de cien contado dos veces por error" },
    },
    {
      id: cashSessionId(3),
      cashier: OTHER,
      status: "closed",
      openedAt: "2026-11-17T08:00:00Z",
      floatCents: 30_000,
      movements: [],
      close: { closedAt: "2026-11-17T17:00:00Z", declaredCents: 30_000, expectedCents: 30_000 },
    },
  ];
  if (option === "mixed") return others;
  const mine: Session = {
    id: cashSessionId(1),
    cashier: ME,
    status: "open",
    openedAt: "2026-11-19T08:00:00Z",
    floatCents: 50_000,
    movements: [
      { id: cashSessionId(11), type: "deposit", cents: 10_000, createdAt: "2026-11-19T09:00:00Z" },
      { id: cashSessionId(12), type: "withdrawal", cents: 5_000, notes: "Entrega a tesorería", createdAt: "2026-11-19T12:00:00Z" },
    ],
  };
  return [mine, ...others];
}

export function createCashboxModel(options: CashboxOptions, now: () => Date = () => new Date()): CashboxModel {
  const sessions = seeded(options.seed);
  const done = new Map<string, { readonly fingerprint: string; readonly response: ContractResponse }>();
  const recorded: RecordedCashWrite[] = [];
  let reads = 0;
  let counter = 100;

  function readFailure(): ModelResponse | undefined {
    if (options.access === "forbidden") return failure("forbidden", 403);
    if (options.reads !== "failing-first") return undefined;
    reads += 1;
    return reads <= FAILING_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  }

  const view = (session: Session): Parameters<typeof cashSession>[0] => ({
      id: session.id,
      cashierId: session.cashier,
      status: session.status,
      openedAt: session.openedAt,
      openingFloat: amountOf(session.floatCents),
      expectedAmount: amountOf(session.close?.expectedCents ?? expectedOf(session)),
      movements: session.movements.map((movement) =>
        cashMovement({ id: movement.id, sessionId: session.id, type: movement.type, amount: amountOf(movement.cents), createdAt: movement.createdAt, createdBy: session.cashier, ...(movement.notes === undefined ? {} : { notes: movement.notes }) }),
      ),
      ...(session.close === undefined
        ? {}
        : {
            close: {
              id: cashSessionId(session.movements.length + 900),
              closedAt: session.close.closedAt,
              closedBy: session.cashier,
              declaredAmount: amountOf(session.close.declaredCents),
              expectedAmount: amountOf(session.close.expectedCents),
              differenceAmount: amountOf(session.close.declaredCents - session.close.expectedCents),
              ...(session.close.reason === undefined ? {} : { differenceReason: session.close.reason }),
            },
          }),
      ...(session.approval === undefined
        ? {}
        : { approval: { id: cashSessionId(950), approvedAt: session.approval.approvedAt, approvedBy: ME, note: session.approval.note } }),
  });

  const answerOf = (session: Session, status = 200): ModelResponse => wrap(cashSession(view(session), status));

  const rowOf = (session: Session) =>
    cashSessionRow({
      id: session.id,
      cashierId: session.cashier,
      status: session.status,
      openedAt: session.openedAt,
      openingFloat: amountOf(session.floatCents),
      ...(session.close === undefined
        ? {}
        : {
            expectedAmount: amountOf(session.close.expectedCents),
            declaredAmount: amountOf(session.close.declaredCents),
            differenceAmount: amountOf(session.close.declaredCents - session.close.expectedCents),
            closedAt: session.close.closedAt,
          }),
    });

  /** The same key and the same request answer the same; the same key and another request is refused. A new key is a new request. */
  function idempotent(request: CashboxRequest, fingerprint: string, operation: () => ModelResponse): ModelResponse {
    const key = request.headers["idempotency-key"];
    if (key === undefined || key === "") return failure("idempotency-key-missing", 400);
    const previous = done.get(key);
    if (previous !== undefined) return previous.fingerprint === fingerprint ? wrap(previous.response) : failure("idempotency-payload-mismatch", 422);
    const answer = operation();
    if (answer.status < 400) done.set(key, { fingerprint, response: { status: answer.status, body: answer.body, contentType: answer.contentType } });
    return answer;
  }

  const record = (operation: RecordedCashWrite["operation"], sessionId: string | undefined, request: CashboxRequest) => {
    recorded.push({ operation, sessionId, idempotencyKey: request.headers["idempotency-key"] ?? "" });
  };

  const money = (value: unknown, field: string): { cents: number } | { error: { field: string; reason: string } } => {
    const amount = typeof value === "object" && value !== null ? (value as { amount?: unknown }).amount : undefined;
    if (typeof amount !== "string") return { error: { field: `${field}.amount`, reason: "required" } };
    return AMOUNT.test(amount) ? { cents: centsOf(amount) } : { error: { field: `${field}.amount`, reason: "pattern" } };
  };

  function open(request: CashboxRequest): ModelResponse {
    if (options.access === "forbidden") return failure("forbidden", 403);
    const float = money((request.body as { openingFloat?: unknown } | undefined)?.openingFloat, "openingFloat");
    if ("error" in float) return invalid([float.error]);
    return idempotent(request, `open\n${String(float.cents)}`, () => {
      if (sessions.some((session) => session.cashier === ME && session.status === "open")) return failure("cash-session-already-open", 409);
      counter += 1;
      const session: Session = { id: cashSessionId(counter), cashier: ME, status: "open", openedAt: now().toISOString(), floatCents: float.cents, movements: [] };
      sessions.push(session);
      record("open", session.id, request);
      return answerOf(session, 201);
    });
  }

  function mine(id: string): Session | undefined {
    return sessions.find((session) => session.id === id && session.cashier === ME);
  }

  function move(id: string, request: CashboxRequest): ModelResponse {
    if (options.access === "forbidden") return failure("forbidden", 403);
    const session = mine(id);
    if (session === undefined) return failure("resource-not-found", 404);
    const body = (request.body ?? {}) as { type?: unknown; amount?: unknown; notes?: unknown };
    const errors: { field: string; reason: string }[] = [];
    const type = (TYPES as readonly unknown[]).includes(body.type) ? (body.type as Movement["type"]) : undefined;
    if (type === undefined) errors.push({ field: "type", reason: "pattern" });
    const amount = money(body.amount, "amount");
    if ("error" in amount) errors.push(amount.error);
    else if (amount.cents <= 0) errors.push({ field: "amount.amount", reason: "positive" });
    if (type === "adjustment") {
      const bad = textProblem(body.notes);
      if (bad !== undefined) errors.push({ field: "notes", reason: bad });
    }
    if (errors.length > 0 || type === undefined || "error" in amount) return invalid(errors);
    const notes = typeof body.notes === "string" && body.notes.trim() !== "" ? body.notes.trim() : undefined;
    return idempotent(request, `move\n${id}\n${type}\n${String(amount.cents)}\n${notes ?? ""}`, () => {
      if (session.status !== "open") return failure("cash-session-not-open", 409);
      if (type === "withdrawal" && amount.cents > expectedOf(session)) return failure("cash-expected-negative", 409);
      const movement: Movement = { id: cashSessionId((counter += 1)), type, cents: amount.cents, createdAt: now().toISOString(), ...(notes === undefined ? {} : { notes }) };
      session.movements.push(movement);
      record("movement", id, request);
      return wrap(
        recordedMovement({ id: movement.id, sessionId: id, type, amount: amountOf(amount.cents), createdAt: movement.createdAt, createdBy: ME, ...(notes === undefined ? {} : { notes }) }, amountOf(expectedOf(session)).amount),
      );
    });
  }

  function close(id: string, request: CashboxRequest): ModelResponse {
    if (options.access === "forbidden") return failure("forbidden", 403);
    const session = mine(id);
    if (session === undefined) return failure("resource-not-found", 404);
    const body = (request.body ?? {}) as { declaredAmount?: unknown; differenceReason?: unknown };
    const declared = money(body.declaredAmount, "declaredAmount");
    if ("error" in declared) return invalid([declared.error]);
    const reasonGiven = typeof body.differenceReason === "string" && body.differenceReason.trim() !== "";
    if (reasonGiven) {
      const bad = textProblem(body.differenceReason);
      if (bad !== undefined) return invalid([{ field: "differenceReason", reason: bad }]);
    }
    const reason = reasonGiven ? (body.differenceReason as string).trim() : undefined;
    return idempotent(request, `close\n${id}\n${String(declared.cents)}\n${reason ?? ""}`, () => {
      if (session.status !== "open") return failure("cash-session-not-open", 409);
      const expected = expectedOf(session);
      if (declared.cents !== expected && reason === undefined) return failure("cash-difference-reason-required", 400);
      session.close = { closedAt: now().toISOString(), declaredCents: declared.cents, expectedCents: expected, ...(declared.cents !== expected && reason !== undefined ? { reason } : {}) };
      session.status = declared.cents === expected ? "closed" : "pending_approval";
      record("close", id, request);
      return answerOf(session);
    });
  }

  function approve(id: string, request: CashboxRequest): ModelResponse {
    if (options.access === "forbidden") return failure("forbidden", 403);
    const session = sessions.find((row) => row.id === id);
    if (session === undefined) return failure("resource-not-found", 404);
    const note = (request.body as { note?: unknown } | undefined)?.note;
    if (typeof note !== "string" || note.trim() === "") return invalid([{ field: "note", reason: "not-blank" }]);
    return idempotent(request, `approve\n${id}\n${note.trim()}`, () => {
      if (session.status !== "pending_approval") return failure("cash-session-not-awaiting-approval", 409);
      if (session.cashier === ME) return failure("cash-approval-same-actor", 403);
      session.approval = { approvedAt: now().toISOString(), note: note.trim() };
      session.status = "closed";
      record("approve", id, request);
      return answerOf(session);
    });
  }

  function list(query: URLSearchParams): ModelResponse {
    const failed = readFailure();
    if (failed !== undefined) return failed;
    const errors: { field: string; reason: string }[] = [];
    const status = query.get("status");
    if (status !== null && !(STATUSES as readonly string[]).includes(status)) errors.push({ field: "status", reason: "pattern" });
    const from = query.get("from");
    const to = query.get("to");
    if (from !== null && !DAY.test(from)) errors.push({ field: "from", reason: "invalid" });
    if (to !== null && !DAY.test(to)) errors.push({ field: "to", reason: "invalid" });
    const [field = "openedAt", direction = "desc"] = (query.get("sort") ?? "openedAt,desc").split(",");
    if (!["openedAt", "status"].includes(field) || (direction !== "asc" && direction !== "desc")) errors.push({ field: "sort", reason: "invalid" });
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) errors.push({ field: "page", reason: "invalid" });
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) errors.push({ field: "size", reason: "invalid" });
    if (errors.length > 0) return invalid(errors);

    const cashier = query.get("cashier");
    const matching = sessions
      .filter((session) => status === null || session.status === status)
      .filter((session) => cashier === null || session.cashier === cashier)
      .filter((session) => from === null || session.openedAt.slice(0, 10) >= from)
      .filter((session) => to === null || session.openedAt.slice(0, 10) <= to);
    const key = (session: Session): string => (field === "status" ? session.status : session.openedAt);
    const ordered = [...matching].sort((a, b) => {
      const order = key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0;
      return direction === "desc" ? -order : order;
    });
    return wrap(cashSessionPage(ordered.slice(page * size, (page + 1) * size).map(rowOf), { page, size, total: ordered.length }));
  }

  return {
    handle(request) {
      const { method, path } = request;
      const patterns = CASHBOX_PATH_PATTERNS;
      if (method === "GET" && patterns.sessions.test(path)) return list(request.query);
      if (method === "POST" && patterns.sessions.test(path)) return open(request);
      if (method === "GET" && patterns.current.test(path)) {
        const failed = readFailure();
        if (failed !== undefined) return failed;
        const current = sessions.find((session) => session.cashier === ME && session.status === "open");
        return current === undefined ? failure("resource-not-found", 404) : answerOf(current);
      }
      const detail = patterns.session.exec(path);
      if (method === "GET" && detail !== null) {
        const failed = readFailure();
        if (failed !== undefined) return failed;
        const found = sessions.find((session) => session.id === decodeURIComponent(detail[1] ?? ""));
        return found === undefined ? failure("resource-not-found", 404) : answerOf(found);
      }
      const moving = patterns.movements.exec(path);
      if (method === "POST" && moving !== null) return move(decodeURIComponent(moving[1] ?? ""), request);
      const closing = patterns.close.exec(path);
      if (method === "POST" && closing !== null) return close(decodeURIComponent(closing[1] ?? ""), request);
      const approving = patterns.approve.exec(path);
      if (method === "POST" && approving !== null) return approve(decodeURIComponent(approving[1] ?? ""), request);
      return undefined;
    },
    writes: () => recorded,
    hasOpenSession: () => sessions.some((session) => session.cashier === ME && session.status === "open"),
    recordPaymentIn(cents) {
      const current = sessions.find((session) => session.cashier === ME && session.status === "open");
      if (current === undefined) return false;
      counter += 1;
      current.movements.push({ id: cashSessionId(counter), type: "payment_in", cents, createdAt: now().toISOString() });
      return true;
    },
  };
}
