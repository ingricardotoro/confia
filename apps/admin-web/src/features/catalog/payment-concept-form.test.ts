import { describe, expect, it } from "vitest";
import { paymentConcept } from "../../test/contract-responses.ts";
import { initialPaymentConceptValues, saveOf, updateBodyOf, validatePaymentConcept } from "./payment-concept-form.ts";

describe("the form of a payment concept", () => {
  it("starts a new concept empty, not recurring, not taxable and active", () => {
    expect(initialPaymentConceptValues()).toEqual({ code: "", name: "", category: "", isRecurring: "false", isTaxable: "false", isActive: "true" });
  });

  it("starts an existing concept with its own values", () => {
    const row = paymentConcept({ code: "TRA", name: "Transporte", category: "TRANSPORT", isRecurring: true, isTaxable: false, isActive: false });

    expect(initialPaymentConceptValues(row)).toEqual({
      code: "TRA",
      name: "Transporte",
      category: "TRANSPORT",
      isRecurring: "true",
      isTaxable: "false",
      isActive: "false",
    });
  });

  it("creates with the code and no state, and edits with the state and no code", () => {
    const row = paymentConcept({ version: 4 });
    const values = { ...initialPaymentConceptValues(row), isTaxable: "false" };

    expect(saveOf(undefined, { ...values, code: "COL" })).toEqual({
      mode: "create",
      body: { code: "COL", name: "Colegiatura", category: "TUITION", isRecurring: true, isTaxable: false },
    });
    expect(saveOf(row, values)).toEqual({
      mode: "update",
      id: row.id,
      body: { version: 4, name: "Colegiatura", category: "TUITION", isRecurring: true, isTaxable: false, isActive: true },
    });
  });

  it("builds the toggle body from the row and the version it was read at, so nothing else changes", () => {
    expect(updateBodyOf(paymentConcept({ isTaxable: true, version: 7 }), false)).toEqual({
      version: 7,
      name: "Colegiatura",
      category: "TUITION",
      isRecurring: true,
      isTaxable: true,
      isActive: false,
    });
  });

  it("asks for a code, a name and a category when creating, and not for the code when editing", () => {
    expect(validatePaymentConcept(undefined)(initialPaymentConceptValues())).toEqual({ code: "required", name: "required", category: "required" });
    expect(validatePaymentConcept(paymentConcept())({ ...initialPaymentConceptValues(paymentConcept()), name: "  " })).toEqual({ name: "required" });
  });

  it("refuses a code or a name that is too long", () => {
    const values = { ...initialPaymentConceptValues(), code: "C".repeat(31), name: "N".repeat(121), category: "OTHER" };

    expect(validatePaymentConcept(undefined)(values)).toEqual({ code: "too-long", name: "too-long" });
  });

  it("accepts a complete new concept", () => {
    expect(validatePaymentConcept(undefined)({ ...initialPaymentConceptValues(), code: "COL", name: "Colegiatura", category: "TUITION" })).toEqual({});
  });
});
