import { describe, expect, it } from "vitest";
import { createStatementModel, DEFAULT_STATEMENT, FAILING_READS, type StatementModel } from "./statement-model.ts";
import { FOREIGN_STUDENT_ID, STATEMENT_STUDENTS } from "./statement-seed.ts";
import { createStudentsModel, DEFAULT_STUDENTS } from "./students-model.ts";
import { studentId } from "./students-seed.ts";

// The simulated statement of account: the shape it answers, the balances it derives from integer cents (the opening balance, the
// running balance of every line, the closing balance), the sections per currency, the default period, the page of lines, the
// validation of the query, and the three refusals (another institution, no permission, a read that fails first). The clock is fixed.
const NOW = () => new Date("2026-11-17T18:00:00Z");
const students = createStudentsModel(DEFAULT_STUDENTS);

function model(override: Partial<typeof DEFAULT_STATEMENT> = {}): StatementModel {
  return createStatementModel({ ...DEFAULT_STATEMENT, ...override }, { students }, NOW);
}

interface Money { amount: string; currency: string }
interface Line { kind: string; effectiveOn: string; debit: Money; credit: Money; balance: Money; reason?: string; charge?: { dueOn: string; conceptName: string } }
interface Section { currency: string; openingBalance: Money; closingBalance: Money; totalLines: number; page: number; size: number; lines: Line[] }
interface Statement { studentCode: string; firstName: string; lastName: string; from: string; to: string; sections: Section[] }

const get = (target: StatementModel, n: number | string, query = "") =>
  target.handle({ method: "GET", path: `/api/v1/charges/students/${typeof n === "number" ? studentId(n) : n}/statement`, query: new URLSearchParams(query) });
const bodyOf = (response: { body?: unknown } | undefined): Statement => response?.body as Statement;
const fieldsOf = (response: { body?: unknown } | undefined) => (response?.body as { errors: { field: string; reason: string }[] }).errors;
const only = (statement: Statement): Section => {
  const [section] = statement.sections;
  if (section === undefined || statement.sections.length !== 1) throw new Error("one section expected");
  return section;
};

describe("the statement of a student", () => {
  it("heads it with the student's code and name and the default period of the last 12 months, in Honduras", () => {
    const body = bodyOf(get(model(), STATEMENT_STUDENTS.twelveMonths));

    expect(body.studentCode).toBe("E-000001");
    expect([body.firstName, body.lastName]).toEqual(["Ana", "Aguilar"]);
    expect([body.from, body.to]).toEqual(["2025-11-17", "2026-11-17"]);
  });

  it("serves twelve monthly charges and two payments with the running balance of each line and the closing balance of the last", () => {
    const section = only(bodyOf(get(model(), STATEMENT_STUDENTS.twelveMonths)));

    expect(section.currency).toBe("HNL");
    expect(section.openingBalance.amount).toBe("0.00");
    expect(section.totalLines).toBe(14);
    expect(section.lines.filter((line) => line.kind === "CHARGE")).toHaveLength(12);
    expect(section.lines[0]).toMatchObject({ kind: "CHARGE", debit: { amount: "1500.00" }, credit: { amount: "0.00" }, balance: { amount: "1500.00" } });
    expect(section.lines.at(-1)?.balance).toEqual(section.closingBalance);
    expect(section.closingBalance.amount).toBe("10500.00");
    // Each balance is the one before plus the line: chosen by hand from the integer cents of the seed.
    expect(section.lines.map((line) => line.balance.amount).slice(0, 4)).toEqual(["1500.00", "3000.00", "4500.00", "6000.00"]);
  });

  it("gives a charge its concept, period and due date, and a payment only its kind and reason", () => {
    const lines = only(bodyOf(get(model(), STATEMENT_STUDENTS.twelveMonths))).lines;

    expect(lines[0]?.charge).toMatchObject({ conceptName: "Colegiatura", periodLabel: expect.stringMatching(/^\d{4}-\d{2}$/) as string });
    const payment = lines.find((line) => line.kind === "PAYMENT");
    expect(payment?.charge).toBeUndefined();
    expect(payment).toMatchObject({ reason: "Pago en caja", debit: { amount: "0.00" } });
  });

  it("derives the opening balance from everything before the period and keeps the lines of the period only", () => {
    const section = only(bodyOf(get(model(), STATEMENT_STUDENTS.twelveMonths, "from=2026-06-01&to=2026-11-17")));

    expect(section.openingBalance.amount).not.toBe("0.00");
    expect((section.lines[0]?.effectiveOn ?? "") >= "2026-06-01").toBe(true);
    expect(section.lines.at(-1)?.balance).toEqual(section.closingBalance);
    expect(section.closingBalance.amount).toBe("10500.00");
  });

  it("includes the first and the last day of the period, and answers a day before every line with the zero balance", () => {
    const target = model();
    const all = only(bodyOf(get(target, STATEMENT_STUDENTS.twelveMonths)));
    const first = all.lines[0]?.effectiveOn ?? "";
    expect(only(bodyOf(get(target, STATEMENT_STUDENTS.twelveMonths, `from=${first}&to=${first}`))).totalLines).toBe(1);
    const before = only(bodyOf(get(target, STATEMENT_STUDENTS.twelveMonths, "from=2020-01-01&to=2020-01-31")));
    expect(before.totalLines).toBe(0);
    expect(before.closingBalance.amount).toBe("0.00");
  });

  it("leaves a balance in the student's favor as a negative figure", () => {
    const section = only(bodyOf(get(model(), STATEMENT_STUDENTS.credit)));

    expect(section.closingBalance.amount).toBe("-300.00");
    expect(section.lines.at(-1)?.balance.amount).toBe("-300.00");
  });

  it("serves no section to a student without an account, and a section in zeros when a currency is asked for", () => {
    const target = model();

    expect(bodyOf(get(target, STATEMENT_STUDENTS.none)).sections).toEqual([]);
    const zero = only(bodyOf(get(target, STATEMENT_STUDENTS.none, "currency=USD")));
    expect(zero).toMatchObject({ currency: "USD", totalLines: 0, lines: [], openingBalance: { amount: "0.00" }, closingBalance: { amount: "0.00" } });
  });

  it("serves a section for each currency of a student with two, and only the one asked for", () => {
    const target = model();

    const both = bodyOf(get(target, STATEMENT_STUDENTS.twoCurrencies)).sections;
    expect(both.map((section) => [section.currency, section.closingBalance.amount])).toEqual([["HNL", "1900.00"], ["USD", "-40.00"]]);
    const usd = bodyOf(get(target, STATEMENT_STUDENTS.twoCurrencies, "currency=USD")).sections;
    expect(usd.map((section) => section.currency)).toEqual(["USD"]);
  });

  it("pages the lines of every section and keeps the running balance across pages", () => {
    const target = model();

    const first = only(bodyOf(get(target, STATEMENT_STUDENTS.manyLines, "page=0&size=25")));
    const third = only(bodyOf(get(target, STATEMENT_STUDENTS.manyLines, "page=2&size=25")));
    expect(first).toMatchObject({ totalLines: 60, page: 0, size: 25 });
    expect(first.lines).toHaveLength(25);
    expect(third.lines).toHaveLength(10);
    expect(third.lines.at(-1)?.balance).toEqual(third.closingBalance);
    expect(only(bodyOf(get(target, STATEMENT_STUDENTS.manyLines, "page=9&size=25"))).lines).toEqual([]);
    expect(only(bodyOf(get(target, STATEMENT_STUDENTS.manyLines))).size).toBe(50);
  });

  it("refuses a currency, a date, a period, a page and a size it does not accept, naming the field", () => {
    const target = model();
    const refused = (query: string) => get(target, STATEMENT_STUDENTS.twelveMonths, query);

    expect(fieldsOf(refused("currency=EUR"))).toEqual([{ field: "currency", reason: "pattern" }]);
    expect(fieldsOf(refused("from=2026-13-40"))).toEqual([{ field: "from", reason: "invalid" }]);
    expect(fieldsOf(refused("to=hoy"))).toEqual([{ field: "to", reason: "invalid" }]);
    expect(fieldsOf(refused("from=2026-02-01&to=2026-01-01"))).toEqual([{ field: "from", reason: "after-to" }]);
    expect(fieldsOf(refused("page=-1"))).toEqual([{ field: "page", reason: "invalid" }]);
    expect(refused("size=0")?.status).toBe(400);
    expect(refused("size=101")?.status).toBe(400);
    expect(refused("from=2026-01-01&to=2026-01-01")?.status).toBe(200);
  });

  it("answers 404 for a student of another institution and 403 without the permission", () => {
    expect(get(model(), FOREIGN_STUDENT_ID)?.status).toBe(404);
    expect(get(model({ access: "forbidden" }), STATEMENT_STUDENTS.twelveMonths)?.status).toBe(403);
  });

  it("serves no account to anyone in the empty scenario", () => {
    expect(bodyOf(get(model({ seed: "empty" }), STATEMENT_STUDENTS.twelveMonths)).sections).toEqual([]);
  });

  it("fails the first reads of the failing scenario with a trace id, and then recovers", () => {
    const failing = model({ reads: "failing-first" });

    const answers = Array.from({ length: FAILING_READS + 1 }, () => get(failing, STATEMENT_STUDENTS.twelveMonths)?.status);
    expect(answers).toEqual([500, 500, 500, 200]);
  });

  it("leaves any other path or method to the other models", () => {
    const target = model();

    expect(target.handle({ method: "POST", path: `/api/v1/charges/students/${studentId(1)}/statement`, query: new URLSearchParams() })).toBeUndefined();
    expect(target.handle({ method: "GET", path: "/api/v1/charges/plans", query: new URLSearchParams() })).toBeUndefined();
  });
});
