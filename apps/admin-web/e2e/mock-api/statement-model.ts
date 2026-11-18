import { problem, statementRecord, studentStatement, type ContractResponse } from "../../src/test/contract-responses.ts";
import { emptyBook, isoOf, seededBook, type SeedLine, type StatementBook } from "./statement-seed.ts";
import type { ModelResponse } from "./session-model.ts";
import type { StudentRows } from "./students-seed.ts";

/**
 * The statement of account of the simulated API (ledger-account-statement cut): the one operation of
 * `GET /api/v1/charges/students/{studentId}/statement`, over lines kept in integer cents for one scenario. It is a thin simulation of
 * the published shape and of only the rules a journey needs: a section per currency the student has (or the one asked for, in zeros
 * when the student has no account in it), the period (the last 12 months by default, from and to included), the opening balance
 * before the period, the running balance of every line, the closing balance, the page of lines, the validation of the query, a student
 * the institution does not have (404) and a person without the permission (403). The balances are computed here from the integer cents
 * of the seed, the way the server computes them from the ledger; the page never does. The real rules, and their limits, are tested in
 * the backend.
 */
export const STATEMENT_PATH_PATTERN = /^\/api\/v1\/charges\/students\/([^/]+)\/statement$/;
/** How many reads fail first in the `failing-first` behavior: the first try and the two retries of the page. */
export const FAILING_READS = 3;
/** A fictitious trace id, the shape the backend gives. */
const TRACE_ID = "01J8Z4K2QF9P0X";
const DEFAULT_SIZE = 50;
const MAX_SIZE = 100;
const CURRENCIES = ["HNL", "USD"] as const;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

export interface StatementOptions {
  /** The book the scenario starts with: students with lines, or nobody with an account. */
  readonly seed: "seeded" | "empty";
  /** `forbidden`: the person lacks `charges:read`, so every read answers 403. */
  readonly access: "allowed" | "forbidden";
  /** `failing-first`: the first reads answer 500, then they recover. */
  readonly reads: "ok" | "failing-first";
}

export const DEFAULT_STATEMENT: StatementOptions = { seed: "seeded", access: "allowed", reads: "ok" };

export interface StatementRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
}

export interface StatementModel {
  /** The answer to a request for a statement, or `undefined` when the path is not that operation. */
  handle(request: StatementRequest): ModelResponse | undefined;
}

const wrap = (response: ContractResponse): ModelResponse => ({ ...response, headers: {}, setCookies: [] });
const failure = (code: Parameters<typeof problem>[0], status: number, options: Parameters<typeof problem>[2] = {}): ModelResponse =>
  wrap(problem(code, status, options));
const invalid = (errors: { field: string; reason: string }[]): ModelResponse => failure("validation-failed", 400, { errors });

/** Whole units and two decimals of an amount in cents, with a minus sign for a negative one (the dollars and the lempiras both have two). */
function amountOf(cents: number, currency: string): { amount: string; currency: string } {
  const sign = cents < 0 ? "-" : "";
  const absolute = Math.abs(cents);
  return { amount: `${sign}${String(Math.floor(absolute / 100))}.${String(absolute % 100).padStart(2, "0")}`, currency };
}

/** Today in Honduras (`yyyy-MM-dd`), the day the server counts the default period from. */
function todayOf(now: Date): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/Tegucigalpa", year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}

/** The same day twelve months earlier (the 29th of February falls on the 28th). */
function twelveMonthsBefore(day: string): string {
  const [year = 0, month = 1, date = 1] = day.split("-").map(Number);
  const target = new Date(Date.UTC(year - 1, month - 1, date));
  return target.getUTCMonth() === month - 1 ? isoOf(target) : isoOf(new Date(Date.UTC(year - 1, month, 0)));
}

function isRealDay(text: string): boolean {
  if (!DAY.test(text)) return false;
  const date = new Date(`${text}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && isoOf(date) === text;
}

/** The canonical order of the server: effective date, posting instant, transaction and line. */
const byCanonicalOrder = (a: SeedLine, b: SeedLine): number =>
  a.effectiveOn.localeCompare(b.effectiveOn) || a.postedAt.localeCompare(b.postedAt) || a.transactionId.localeCompare(b.transactionId) || a.lineNumber - b.lineNumber;

export function createStatementModel(
  options: StatementOptions,
  dependencies: { readonly students: { rows(): Readonly<StudentRows> } },
  now: () => Date = () => new Date(),
): StatementModel {
  const book: StatementBook = options.seed === "seeded" ? seededBook(now()) : emptyBook();
  let reads = 0;

  function readFailure(): ModelResponse | undefined {
    if (options.reads !== "failing-first") return undefined;
    reads += 1;
    return reads <= FAILING_READS ? failure("internal-error", 500, { traceId: TRACE_ID }) : undefined;
  }

  function statement(id: string, query: URLSearchParams): ModelResponse {
    if (options.access === "forbidden") return failure("forbidden", 403);
    const failed = readFailure();
    if (failed !== undefined) return failed;
    const student = dependencies.students.rows().students.find((row) => row.id === id);
    if (student === undefined) return failure("resource-not-found", 404);

    const errors: { field: string; reason: string }[] = [];
    const currency = query.get("currency");
    if (currency !== null && !(CURRENCIES as readonly string[]).includes(currency)) errors.push({ field: "currency", reason: "pattern" });
    const today = todayOf(now());
    const to = query.get("to") ?? today;
    const from = query.get("from") ?? (isRealDay(to) ? twelveMonthsBefore(to) : to);
    if (query.has("from") && !isRealDay(from)) errors.push({ field: "from", reason: "invalid" });
    if (query.has("to") && !isRealDay(to)) errors.push({ field: "to", reason: "invalid" });
    if (errors.length === 0 && from > to) errors.push({ field: "from", reason: "after-to" });
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? String(DEFAULT_SIZE));
    if (!Number.isInteger(page) || page < 0) errors.push({ field: "page", reason: "invalid" });
    if (!Number.isInteger(size) || size < 1 || size > MAX_SIZE) errors.push({ field: "size", reason: "invalid" });
    if (errors.length > 0) return invalid(errors);

    const account = book.get(id) ?? new Map<string, SeedLine[]>();
    // A section per currency the student has; with a currency asked for, only that one, in zeros when the student has none in it.
    const wanted = currency === null ? CURRENCIES.filter((code) => account.has(code)) : [currency];
    const sections = wanted.map((code) => {
      const ordered = [...(account.get(code) ?? [])].sort(byCanonicalOrder);
      const net = (line: SeedLine): number => line.debitCents - line.creditCents;
      const openingCents = ordered.filter((line) => line.effectiveOn < from).reduce((sum, line) => sum + net(line), 0);
      const inPeriod = ordered.filter((line) => line.effectiveOn >= from && line.effectiveOn <= to);
      let running = openingCents;
      const lines = inPeriod.map((line) => {
        running += net(line);
        return { line, balanceCents: running };
      });
      const pageLines = lines.slice(page * size, (page + 1) * size).map(({ line, balanceCents }) => ({
        transactionId: line.transactionId,
        kind: line.kind,
        effectiveOn: line.effectiveOn,
        postedAt: line.postedAt,
        lineNumber: line.lineNumber,
        ...(line.reason === undefined ? {} : { reason: line.reason }),
        debit: amountOf(line.debitCents, code),
        credit: amountOf(line.creditCents, code),
        balance: amountOf(balanceCents, code),
        ...(line.charge === undefined ? {} : { charge: { ...line.charge, origin: "AUTOMATIC" as const } }),
      }));
      return {
        currency: code,
        openingBalance: amountOf(openingCents, code),
        closingBalance: amountOf(running, code),
        totalLines: inPeriod.length,
        page,
        size,
        lines: pageLines,
      };
    });

    return wrap(
      statementRecord(
        studentStatement({ studentId: student.id, studentCode: student.studentCode, firstName: student.firstName, lastName: student.lastName, from, to, sections }),
      ),
    );
  }

  return {
    handle(request) {
      const match = STATEMENT_PATH_PATTERN.exec(request.path);
      if (request.method !== "GET" || match === null) return undefined;
      return statement(decodeURIComponent(match[1] ?? ""), request.query);
    },
  };
}
