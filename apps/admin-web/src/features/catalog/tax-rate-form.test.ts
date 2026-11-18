import { describe, expect, it } from "vitest";
import { taxRate } from "../../test/contract-responses.ts";
import { initialTaxRateValues, saveOf, stateBodyOf, validateTaxRate } from "./tax-rate-form.ts";

describe("the form of a tax rate", () => {
  it("starts a new rate empty and an existing one with its own code and name", () => {
    expect(initialTaxRateValues()).toEqual({ code: "", name: "" });
    expect(initialTaxRateValues(taxRate({ code: "IMP-A", name: "Impuesto de prueba A" }))).toEqual({ code: "IMP-A", name: "Impuesto de prueba A" });
  });

  it("creates with a code and a name only, and edits with the name, the version and the state the rate has, and no code", () => {
    const row = taxRate({ version: 4, isActive: false });
    const values = initialTaxRateValues(row);

    expect(saveOf(undefined, { code: "IMP-B", name: "Impuesto B" })).toEqual({ mode: "create", body: { code: "IMP-B", name: "Impuesto B" } });
    expect(saveOf(row, { ...values, name: "Otro nombre" })).toEqual({
      mode: "update",
      id: row.id,
      body: { version: 4, name: "Otro nombre", isActive: false },
    });
  });

  it("builds the body of a state change from the version the rate was read at, keeping the name", () => {
    expect(stateBodyOf(taxRate({ version: 7, name: "Impuesto de prueba A" }), false)).toEqual({ version: 7, name: "Impuesto de prueba A", isActive: false });
  });

  it("asks for a code and a name when creating, and for the name only when editing", () => {
    expect(validateTaxRate(undefined)(initialTaxRateValues())).toEqual({ code: "required", name: "required" });
    expect(validateTaxRate(taxRate())({ ...initialTaxRateValues(taxRate()), name: "  " })).toEqual({ name: "required" });
  });

  it("refuses a code or a name that is too long, and a code outside the whitelist with its own reason", () => {
    expect(validateTaxRate(undefined)({ code: "C".repeat(31), name: "N".repeat(121) })).toEqual({ code: "too-long", name: "too-long" });
    for (const code of ["IM P", "-IMP", "ÍMP", "I.P"]) {
      expect(validateTaxRate(undefined)({ code, name: "Impuesto" }), code).toEqual({ code: "code-format" });
    }
  });

  it("accepts a complete new rate", () => {
    expect(validateTaxRate(undefined)({ code: "IMP-A", name: "Impuesto de prueba A" })).toEqual({});
  });
});
