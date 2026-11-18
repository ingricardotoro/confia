import { describe, expect, it } from "vitest";
import { statementLine } from "../../test/contract-responses.ts";
import { balanceSideOf, currencyNameOf, describeLine, directionOf, dueOnOf, periodProblemOf } from "./statement-model.ts";

const money = (amount: string) => ({ amount, currency: "HNL" });

describe("balanceSideOf", () => {
  it.each([
    ["1500.00", "owed"],
    ["0.01", "owed"],
    ["-1500.00", "credit"],
    ["-0.01", "credit"],
    ["0.00", "zero"],
    ["0", "zero"],
    ["-0.00", "zero"],
  ] as const)("reads %s as %s from the text alone", (amount, side) => {
    expect(balanceSideOf(money(amount))).toBe(side);
  });
});

describe("periodProblemOf", () => {
  it("accepts a period that starts before it ends, on the same day and with a side missing", () => {
    expect(periodProblemOf("2027-01-01", "2027-02-01")).toBeUndefined();
    expect(periodProblemOf("2027-01-01", "2027-01-01")).toBeUndefined();
    expect(periodProblemOf("2027-01-01", undefined)).toBeUndefined();
    expect(periodProblemOf(undefined, "2027-01-01")).toBeUndefined();
    expect(periodProblemOf("", "2027-01-01")).toBeUndefined();
  });

  it("refuses a period that ends before it starts, across a month and a year", () => {
    expect(periodProblemOf("2027-02-01", "2027-01-31")).toBe("from-after-to");
    expect(periodProblemOf("2027-01-01", "2026-12-31")).toBe("from-after-to");
  });
});

describe("describeLine", () => {
  it("describes a charge by its concept name and period", () => {
    expect(describeLine(statementLine())).toEqual({ kind: "charge", concept: "Colegiatura", periodStart: "2027-02-01" });
  });

  it("falls back to the code when the concept has no name, and to nothing when it has neither", () => {
    const base = statementLine();
    const charge = base.charge;
    if (charge === undefined) throw new Error("the fixture has a charge");
    expect(describeLine({ ...base, charge: { ...charge, conceptName: null } })).toMatchObject({ concept: "COL" });
    expect(describeLine({ ...base, charge: { ...charge, conceptName: null, conceptCode: null } })).toMatchObject({ concept: undefined });
  });

  it("describes any other movement by its kind and reason", () => {
    const line = statementLine({ kind: "PAYMENT", reason: "Pago en caja", charge: undefined });
    expect(describeLine(line)).toEqual({ kind: "movement", lineKind: "PAYMENT", reason: "Pago en caja" });
    expect(describeLine(statementLine({ kind: "ADJUSTMENT", reason: null, charge: undefined }))).toMatchObject({ reason: undefined });
  });
});

describe("dueOnOf and directionOf", () => {
  it("takes the due date from the charge, and has none for another movement", () => {
    expect(dueOnOf(statementLine())).toBe("2027-02-10");
    expect(dueOnOf(statementLine({ charge: undefined }))).toBeUndefined();
  });

  it("names the column that holds the figure", () => {
    expect(directionOf(statementLine())).toBe("debit");
    expect(directionOf(statementLine({ debit: money("0.00"), credit: money("500.00") }))).toBe("credit");
    expect(directionOf(statementLine({ debit: money("0.00"), credit: money("0.00") }))).toBeUndefined();
  });
});

describe("currencyNameOf", () => {
  it("gives the name Intl knows and the code when it is not a code", () => {
    expect(currencyNameOf("HNL").toLowerCase()).toContain("lempira");
    expect(currencyNameOf("not a code")).toBe("not a code");
  });
});
