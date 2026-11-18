import { conceptId } from "./catalog-seed.ts";
import { studentId } from "./students-seed.ts";

/**
 * The fictitious statements of account of the simulated API (ledger-account-statement cut). Plain data in integer cents, with
 * fixed identifiers, so a journey can name a student and a test can assert on a figure. The dates are relative to the day the
 * scenario is built, so the default period (the last 12 months) always holds them. Nothing here is imported by the app or by the
 * build. The simulator, like the server, derives balances from these lines; the browser never does.
 */
export type StatementKind = "CHARGE" | "PAYMENT" | "DISCOUNT" | "LATE_FEE" | "CREDIT_NOTE" | "REVERSAL" | "ADJUSTMENT" | "WRITE_OFF";

export interface SeedCharge {
  chargeId: string;
  paymentConceptId: string;
  conceptCode: string;
  conceptName: string;
  conceptCategory: string;
  periodLabel: string;
  periodStart: string;
  periodEnd: string;
  dueOn: string;
}

export interface SeedLine {
  transactionId: string;
  kind: StatementKind;
  effectiveOn: string;
  postedAt: string;
  lineNumber: number;
  debitCents: number;
  creditCents: number;
  reason?: string;
  charge?: SeedCharge;
}

/** The lines of each student, by currency: a student with no entry has no account at all. */
export type StatementBook = Map<string, Map<string, SeedLine[]>>;

/** The student that exists in no institution of the simulator: its statement answers 404, like a student of another institution. */
export const FOREIGN_STUDENT_ID = "00000000-0000-4000-8000-000000009404";

/** The numbers of the seeded students (`studentId(n)`) that each scenario of the statement uses. */
export const STATEMENT_STUDENTS = {
  /** Twelve monthly charges and two payments, in lempiras. */
  twelveMonths: 1,
  /** Three charges and a payment that leaves a balance in the student's favor. */
  credit: 2,
  /** No account and no movement. */
  none: 3,
  /** Lempiras and dollars, the dollars in the student's favor. */
  twoCurrencies: 4,
  /** Sixty lines, so the statement has three pages of 25. */
  manyLines: 5,
} as const;

const DAY_MS = 86_400_000;

export const isoOf = (date: Date): string => date.toISOString().slice(0, 10);
const addDays = (date: Date, days: number): Date => new Date(date.getTime() + days * DAY_MS);
/** The first day of the month `monthsBack` months before the one of `today`. */
const monthStart = (today: Date, monthsBack: number): Date => new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() - monthsBack, 1));
const endOfMonth = (start: Date): Date => new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0));
const hex = (prefix: string, n: number, index: number): string => `00000000-0000-4000-8000-${prefix}${String(n * 1000 + index).padStart(11, "0")}`;

function chargeLine(n: number, index: number, start: Date, cents: number, concept: { n: number; code: string; name: string; category: string }): SeedLine {
  const end = endOfMonth(start);
  return {
    transactionId: hex("a", n, index),
    kind: "CHARGE",
    effectiveOn: isoOf(start),
    postedAt: `${isoOf(start)}T14:00:00Z`,
    lineNumber: 1,
    debitCents: cents,
    creditCents: 0,
    charge: {
      chargeId: hex("b", n, index),
      paymentConceptId: conceptId(concept.n),
      conceptCode: concept.code,
      conceptName: concept.name,
      conceptCategory: concept.category,
      periodLabel: isoOf(start).slice(0, 7),
      periodStart: isoOf(start),
      periodEnd: isoOf(end),
      dueOn: isoOf(addDays(start, 9)),
    },
  };
}

function paymentLine(n: number, index: number, on: Date, cents: number, reason: string): SeedLine {
  return {
    transactionId: hex("a", n, index),
    kind: "PAYMENT",
    effectiveOn: isoOf(on),
    postedAt: `${isoOf(on)}T16:00:00Z`,
    lineNumber: 1,
    debitCents: 0,
    creditCents: cents,
    reason,
  };
}

const TUITION = { n: 1, code: "COL", name: "Colegiatura", category: "TUITION" } as const;
const TRANSPORT = { n: 3, code: "TRA", name: "Transporte escolar", category: "TRANSPORT" } as const;

/** The seeded book, relative to `today` (the date the simulator calls today, in Honduras). */
export function seededBook(today: Date): StatementBook {
  const book: StatementBook = new Map();

  // Twelve months of tuition: the last twelve first days of a month, a payment after the third and another after the eighth.
  const year: SeedLine[] = Array.from({ length: 12 }, (_, i) => chargeLine(STATEMENT_STUDENTS.twelveMonths, i + 1, monthStart(today, 11 - i), 150_000, TUITION));
  year.push(paymentLine(STATEMENT_STUDENTS.twelveMonths, 101, addDays(monthStart(today, 8), 4), 300_000, "Pago en caja"));
  year.push(paymentLine(STATEMENT_STUDENTS.twelveMonths, 102, addDays(monthStart(today, 3), 4), 450_000, "Pago en caja"));
  book.set(studentId(STATEMENT_STUDENTS.twelveMonths), new Map([["HNL", year]]));

  // Credit: three charges of 900.00 and a payment of 3,000.00.
  const credit: SeedLine[] = Array.from({ length: 3 }, (_, i) => chargeLine(STATEMENT_STUDENTS.credit, i + 1, monthStart(today, 2 - i), 90_000, TUITION));
  credit.push(paymentLine(STATEMENT_STUDENTS.credit, 101, addDays(monthStart(today, 1), 2), 300_000, "Pago adelantado"));
  book.set(studentId(STATEMENT_STUDENTS.credit), new Map([["HNL", credit]]));

  // Two currencies: lempiras owed, dollars in the student's favor.
  const hnl: SeedLine[] = [
    chargeLine(STATEMENT_STUDENTS.twoCurrencies, 1, monthStart(today, 1), 120_000, TUITION),
    chargeLine(STATEMENT_STUDENTS.twoCurrencies, 2, monthStart(today, 0), 120_000, TUITION),
    paymentLine(STATEMENT_STUDENTS.twoCurrencies, 101, addDays(monthStart(today, 1), 6), 50_000, "Pago en caja"),
  ];
  const usd: SeedLine[] = [
    chargeLine(STATEMENT_STUDENTS.twoCurrencies, 201, monthStart(today, 1), 6_000, TRANSPORT),
    paymentLine(STATEMENT_STUDENTS.twoCurrencies, 301, addDays(monthStart(today, 1), 7), 10_000, "Pago en dólares"),
  ];
  book.set(studentId(STATEMENT_STUDENTS.twoCurrencies), new Map([["HNL", hnl], ["USD", usd]]));

  // Sixty lines, one every six days, a payment for every third.
  const many: SeedLine[] = Array.from({ length: 60 }, (_, i) => {
    const on = addDays(today, -358 + i * 6);
    return i % 3 === 2
      ? paymentLine(STATEMENT_STUDENTS.manyLines, i + 1, on, 25_000, "Pago en caja")
      : chargeLine(STATEMENT_STUDENTS.manyLines, i + 1, new Date(Date.UTC(on.getUTCFullYear(), on.getUTCMonth(), on.getUTCDate())), 25_000, TUITION);
  });
  book.set(studentId(STATEMENT_STUDENTS.manyLines), new Map([["HNL", many]]));

  return book;
}

export const emptyBook = (): StatementBook => new Map();
