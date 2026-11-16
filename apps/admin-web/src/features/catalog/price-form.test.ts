import { describe, expect, it } from "vitest";
import { priceListItem } from "../../test/contract-responses.ts";
import { addBodyOf, groupByConcept, initialPriceValues, validatePrice, type PriceValues } from "./price-form.ts";

const CONCEPT = "00000000-0000-4000-8000-000000007001";
const complete: PriceValues = { paymentConceptId: CONCEPT, amount: "1500.00", currency: "HNL", validFrom: "2027-01-01", validTo: "" };

describe("the form of a price", () => {
  it("starts in lempiras with everything else to be filled", () => {
    expect(initialPriceValues()).toEqual({ paymentConceptId: "", amount: "", currency: "HNL", validFrom: "", validTo: "" });
  });

  it("sends the amount exactly as it was typed, never as a number, and leaves out an end date that is empty", () => {
    const body = addBodyOf({ ...complete, amount: "1500.5000" });

    expect(body).toEqual({ paymentConceptId: CONCEPT, price: { amount: "1500.5000", currency: "HNL" }, validFrom: "2027-01-01" });
    expect(typeof body.price.amount).toBe("string");
    expect(addBodyOf({ ...complete, validTo: "2027-06-30" }).validTo).toBe("2027-06-30");
  });

  it("accepts a complete price, with or without an end date, and an end on the very day it starts", () => {
    expect(validatePrice(complete)).toEqual({});
    expect(validatePrice({ ...complete, validTo: "2027-12-31" })).toEqual({});
    expect(validatePrice({ ...complete, validTo: "2027-01-01" })).toEqual({});
  });

  it("asks for the concept, the amount and the start", () => {
    expect(validatePrice(initialPriceValues())).toEqual({ paymentConceptId: "required", amount: "required", validFrom: "required" });
  });

  it.each([
    ["0", "amount-positive"],
    ["0.00", "amount-positive"],
    ["0.0000", "amount-positive"],
    ["-5", "amount-positive"],
    ["-0.01", "amount-positive"],
    ["1500.12345", "amount-format"],
    ["12345678901", "amount-format"],
    ["1,500.00", "amount-format"],
    ["1500,00", "amount-format"],
    ["abc", "amount-format"],
    [".5", "amount-format"],
    ["1e3", "amount-format"],
  ] as const)("refuses the amount %s as %s, judging only its characters", (amount, reason) => {
    expect(validatePrice({ ...complete, amount })).toEqual({ amount: reason });
  });

  it.each(["0.0001", "1", "9999999999.9999", "1500.5"])("accepts the amount %s", (amount) => {
    expect(validatePrice({ ...complete, amount })).toEqual({});
  });

  it("refuses an end date before the start", () => {
    expect(validatePrice({ ...complete, validTo: "2026-12-31" })).toEqual({ validTo: "validity-order" });
  });

  it("refuses a currency the system does not operate with", () => {
    expect(validatePrice({ ...complete, currency: "EUR" })).toEqual({ currency: "format" });
    expect(validatePrice({ ...complete, currency: "" })).toEqual({ currency: "required" });
  });
});

describe("the prices of a list grouped by concept", () => {
  const other = "00000000-0000-4000-8000-000000007002";

  it("keeps one group for each concept in the order each appears, and its validities from the newest start to the oldest", () => {
    const first = priceListItem({ paymentConceptId: CONCEPT, validFrom: "2027-01-01", validTo: "2027-06-30" });
    const second = priceListItem({ paymentConceptId: other, validFrom: "2027-02-01" });
    const third = priceListItem({ paymentConceptId: CONCEPT, validFrom: "2027-07-01" });

    const groups = groupByConcept([first, second, third]);

    expect(groups.map((group) => group.paymentConceptId)).toEqual([CONCEPT, other]);
    expect(groups[0]?.items).toEqual([third, first]);
    expect(groups[1]?.items).toEqual([second]);
  });

  it("returns nothing for a list with no prices, and never changes an item", () => {
    const item = priceListItem({ price: { amount: "1650.00", currency: "HNL" } });

    expect(groupByConcept([])).toEqual([]);
    expect(groupByConcept([item])[0]?.items[0]).toBe(item);
  });
});
