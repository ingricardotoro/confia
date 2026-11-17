import { describe, expect, it } from "vitest";
import { paymentConcept } from "../../test/contract-responses.ts";
import { initialPaymentConceptValues, saveOf, updateBodyOf, validatePaymentConcept } from "./payment-concept-form.ts";

const RATE = "00000000-0000-4000-8000-0000000000a1";

describe("the form of a payment concept", () => {
  it("starts a new concept empty, not recurring, not taxable and active", () => {
    expect(initialPaymentConceptValues()).toEqual({ code: "", name: "", category: "", isRecurring: "false", isTaxable: "false", taxRateId: "", isActive: "true" });
  });

  it("starts an existing concept with its own values", () => {
    const row = paymentConcept({ code: "TRA", name: "Transporte", category: "TRANSPORT", isRecurring: true, isTaxable: false, isActive: false });

    expect(initialPaymentConceptValues(row)).toEqual({
      code: "TRA",
      name: "Transporte",
      category: "TRANSPORT",
      isRecurring: "true",
      isTaxable: "false",
      taxRateId: "",
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

  it("starts an existing taxable concept with the rate it holds", () => {
    expect(initialPaymentConceptValues(paymentConcept({ isTaxable: true, taxRateId: RATE })).taxRateId).toBe(RATE);
    expect(initialPaymentConceptValues(paymentConcept({ isTaxable: true, taxRateId: null })).taxRateId).toBe("");
  });

  it("sends the rate only for a taxable concept, and drops it when the concept is not taxable", () => {
    const values = { ...initialPaymentConceptValues(), code: "COL", name: "Colegiatura", category: "TUITION", taxRateId: RATE };

    expect(saveOf(undefined, { ...values, isTaxable: "true" })).toEqual({
      mode: "create",
      body: { code: "COL", name: "Colegiatura", category: "TUITION", isRecurring: false, isTaxable: true, taxRateId: RATE },
    });
    expect(saveOf(undefined, { ...values, isTaxable: "false" })).toEqual({
      mode: "create",
      body: { code: "COL", name: "Colegiatura", category: "TUITION", isRecurring: false, isTaxable: false },
    });
    const row = paymentConcept({ version: 1, isTaxable: true, taxRateId: RATE });
    expect(saveOf(row, { ...initialPaymentConceptValues(row), isTaxable: "false", taxRateId: "" })).toEqual({
      mode: "update",
      id: row.id,
      body: { version: 1, name: "Colegiatura", category: "TUITION", isRecurring: true, isTaxable: false, isActive: true },
    });
  });

  it("keeps the rate in the toggle body of a taxable concept, and sends none for one that is not taxable", () => {
    expect(updateBodyOf(paymentConcept({ isTaxable: true, taxRateId: RATE, version: 2 }), false)).toMatchObject({ isTaxable: true, taxRateId: RATE });
    expect(updateBodyOf(paymentConcept({ isTaxable: false, taxRateId: null, version: 2 }), false)).not.toHaveProperty("taxRateId");
  });

  it("asks for a rate when the concept is taxable, and for none when it is not", () => {
    const filled = { ...initialPaymentConceptValues(), code: "COL", name: "Colegiatura", category: "TUITION" };

    expect(validatePaymentConcept(undefined)({ ...filled, isTaxable: "true" })).toEqual({ taxRateId: "required" });
    expect(validatePaymentConcept(undefined)({ ...filled, isTaxable: "true", taxRateId: RATE })).toEqual({});
    expect(validatePaymentConcept(undefined)({ ...filled, isTaxable: "false" })).toEqual({});
  });

  it("asks for a code, a name and a category when creating, and not for the code when editing", () => {
    expect(validatePaymentConcept(undefined)(initialPaymentConceptValues())).toEqual({ code: "required", name: "required", category: "required" });
    expect(validatePaymentConcept(paymentConcept({ isTaxable: false }))({ ...initialPaymentConceptValues(paymentConcept({ isTaxable: false })), name: "  " })).toEqual({ name: "required" });
  });

  it("refuses a code or a name that is too long", () => {
    const values = { ...initialPaymentConceptValues(), code: "C".repeat(31), name: "N".repeat(121), category: "OTHER" };

    expect(validatePaymentConcept(undefined)(values)).toEqual({ code: "too-long", name: "too-long" });
  });

  it("accepts a complete new concept", () => {
    expect(validatePaymentConcept(undefined)({ ...initialPaymentConceptValues(), code: "COL", name: "Colegiatura", category: "TUITION" })).toEqual({});
  });
});
