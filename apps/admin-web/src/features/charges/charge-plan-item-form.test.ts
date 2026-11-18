import { describe, expect, it } from "vitest";
import { addBodyOf, firstPeriodReason, initialChargePlanItemValues, periodsReason, validateChargePlanItem } from "./charge-plan-item-form.ts";

const CONCEPT = "00000000-0000-4000-8000-000000007001";
const COMPLETE = { paymentConceptId: CONCEPT, periodsCount: "10", firstPeriodOn: "2027-02-01" } as const;

describe("the form of a new item", () => {
  it("starts empty and asks for the three fields", () => {
    expect(validateChargePlanItem(initialChargePlanItemValues())).toEqual({ paymentConceptId: "required", periodsCount: "required", firstPeriodOn: "required" });
  });

  it("accepts a complete item and sends the periods as a whole number and the day as the text typed", () => {
    expect(validateChargePlanItem(COMPLETE)).toEqual({});
    expect(addBodyOf(COMPLETE)).toEqual({ paymentConceptId: CONCEPT, periodsCount: 10, firstPeriodOn: "2027-02-01" });
  });

  it.each(["0", "37", "-1", "1.5", "abc", "1000"])("refuses %s periods: they are from 1 to 36", (count) => {
    expect(periodsReason(count)).toBe("periods-range");
    expect(validateChargePlanItem({ ...COMPLETE, periodsCount: count })).toEqual({ periodsCount: "periods-range" });
  });

  it.each(["1", "12", "36"])("accepts %s periods", (count) => {
    expect(periodsReason(count)).toBeUndefined();
  });

  it.each(["2027-02-02", "2027-02-15", "2027-02-28", "2027-12-31"])("refuses %s: a period starts on the first of a month", (day) => {
    expect(firstPeriodReason(day)).toBe("first-of-month");
    expect(validateChargePlanItem({ ...COMPLETE, firstPeriodOn: day })).toEqual({ firstPeriodOn: "first-of-month" });
  });

  it("accepts the first of any month inside the window, and refuses a day outside it or text that is not a date", () => {
    expect(firstPeriodReason("2000-01-01")).toBeUndefined();
    expect(firstPeriodReason("2100-12-01")).toBeUndefined();
    expect(firstPeriodReason("1999-12-01")).toBe("date-range");
    expect(firstPeriodReason("2101-01-01")).toBe("date-range");
    expect(firstPeriodReason("1/2/2027")).toBe("format");
    expect(firstPeriodReason("")).toBe("required");
  });

  it("refuses a concept that is not an id", () => {
    expect(validateChargePlanItem({ ...COMPLETE, paymentConceptId: "colegiatura" })).toEqual({ paymentConceptId: "format" });
  });
});
