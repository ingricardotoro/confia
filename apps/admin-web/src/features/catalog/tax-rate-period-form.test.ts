import { describe, expect, it } from "vitest";
import { taxRatePeriod } from "../../test/contract-responses.ts";
import { addBodyOf, initialTaxRatePeriodValues, newestFirst, validateTaxRatePeriod } from "./tax-rate-period-form.ts";

const valid = { ratePercent: "12.34", validFrom: "2027-01-01", validTo: "" };

describe("the form of a new validity", () => {
  it("starts empty", () => {
    expect(initialTaxRatePeriodValues()).toEqual({ ratePercent: "", validFrom: "", validTo: "" });
  });

  it("sends the percentage as the text that was typed, and no end date when there is none", () => {
    expect(addBodyOf(valid)).toEqual({ ratePercent: "12.34", validFrom: "2027-01-01" });
    expect(addBodyOf({ ...valid, ratePercent: "7.5000", validTo: "2027-12-31" })).toEqual({ ratePercent: "7.5000", validFrom: "2027-01-01", validTo: "2027-12-31" });
    expect(typeof addBodyOf(valid).ratePercent).toBe("string");
  });

  it("accepts a complete validity, with and without an end", () => {
    expect(validateTaxRatePeriod(valid)).toEqual({});
    expect(validateTaxRatePeriod({ ...valid, validTo: "2027-12-31" })).toEqual({});
    expect(validateTaxRatePeriod({ ratePercent: "100", validFrom: "2000-01-01", validTo: "2100-12-31" })).toEqual({});
    expect(validateTaxRatePeriod({ ...valid, ratePercent: "0.0001" })).toEqual({});
    expect(validateTaxRatePeriod({ ...valid, ratePercent: "100.0000" })).toEqual({});
  });

  it("asks for the percentage and the start", () => {
    expect(validateTaxRatePeriod({ ratePercent: "", validFrom: "", validTo: "" })).toEqual({ ratePercent: "required", validFrom: "required" });
  });

  it.each(["abc", "1,5", "1.", ".5", "12.34567", "1234", "1e2", "+5", "5 %", "1 2"])("refuses %s as a percentage that is not written as the server takes it", (rate) => {
    expect(validateTaxRatePeriod({ ...valid, ratePercent: rate })).toEqual({ ratePercent: "rate-format" });
  });

  it.each(["0", "0.0", "0.0000", "000", "-5", "-0.5", "100.0001", "101", "999", "100.5", "101.0000"])(
    "refuses %s as a percentage that is not above zero and at most 100",
    (rate) => {
      expect(validateTaxRatePeriod({ ...valid, ratePercent: rate })).toEqual({ ratePercent: "rate-range" });
    },
  );

  it("judges the percentage on its characters: it never becomes a number", () => {
    // 0.1 + 0.2 and long fractions are not exact as numbers; the text stays as typed and is judged as text.
    expect(validateTaxRatePeriod({ ...valid, ratePercent: "99.9999" })).toEqual({});
    expect(validateTaxRatePeriod({ ...valid, ratePercent: "0099" })).toEqual({ ratePercent: "rate-format" });
    expect(validateTaxRatePeriod({ ...valid, ratePercent: "07.5" })).toEqual({});
  });

  it("refuses a date outside 2000-01-01 to 2100-12-31, with the reason of the window", () => {
    expect(validateTaxRatePeriod({ ...valid, validFrom: "1999-12-31" })).toEqual({ validFrom: "date-range" });
    expect(validateTaxRatePeriod({ ...valid, validFrom: "2101-01-01" })).toEqual({ validFrom: "date-range" });
    expect(validateTaxRatePeriod({ ...valid, validTo: "2101-01-01" })).toEqual({ validTo: "date-range" });
    expect(validateTaxRatePeriod({ ...valid, validTo: "1999-12-31" })).toEqual({ validTo: "date-range" });
  });

  it("refuses an end before the start, and takes an end on the same day", () => {
    expect(validateTaxRatePeriod({ ...valid, validTo: "2026-12-31" })).toEqual({ validTo: "validity-order" });
    expect(validateTaxRatePeriod({ ...valid, validTo: "2027-01-01" })).toEqual({});
  });

  it("arranges the validities from the newest start to the oldest without changing any", () => {
    const old = taxRatePeriod({ validFrom: "2026-01-01", validTo: "2026-12-31" });
    const current = taxRatePeriod({ validFrom: "2027-01-01" });
    const middle = taxRatePeriod({ validFrom: "2026-06-01", validTo: "2026-12-31" });
    const input = [old, current, middle];

    expect(newestFirst(input)).toEqual([current, middle, old]);
    expect(input).toEqual([old, current, middle]);
  });
});
