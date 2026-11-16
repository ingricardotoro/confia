import { describe, expect, it } from "vitest";
import { priceList } from "../../test/contract-responses.ts";
import { createBodyOf, initialPriceListValues, needsReference, statusBodyOf, validatePriceList } from "./price-list-form.ts";

const YEAR = "00000000-0000-4000-8000-000000000a01";
const GRADE = "00000000-0000-4000-8000-000000000c01";

describe("the form of a price list", () => {
  it("starts for the whole institution with the year and the name to be filled", () => {
    expect(initialPriceListValues()).toEqual({ academicYearId: "", scope: "INSTITUTION", scopeRefId: "", name: "" });
  });

  it("asks for a reference only for a modality or a grade", () => {
    expect([needsReference("INSTITUTION"), needsReference("MODALITY"), needsReference("GRADE"), needsReference("")]).toEqual([false, true, true, false]);
  });

  it("sends no reference for the whole institution, even if one was chosen before the scope changed", () => {
    const values = { academicYearId: YEAR, scope: "INSTITUTION", scopeRefId: GRADE, name: "2027 — Institución" };

    expect(createBodyOf(values)).toEqual({ academicYearId: YEAR, name: "2027 — Institución", scope: "INSTITUTION" });
  });

  it("sends the reference for a grade", () => {
    expect(createBodyOf({ academicYearId: YEAR, scope: "GRADE", scopeRefId: GRADE, name: "Primero" })).toEqual({
      academicYearId: YEAR,
      name: "Primero",
      scope: "GRADE",
      scopeRefId: GRADE,
    });
  });

  it("asks for the year and the name, and not for a reference when the scope is the institution", () => {
    expect(validatePriceList(initialPriceListValues())).toEqual({ academicYearId: "required", name: "required" });
  });

  it("asks for the modality or the grade when the scope needs one", () => {
    expect(validatePriceList({ academicYearId: YEAR, scope: "MODALITY", scopeRefId: "", name: "Nocturna" })).toEqual({ scopeRefId: "required" });
  });

  it("refuses a name that is too long or has a control character, and accepts a complete list", () => {
    expect(validatePriceList({ academicYearId: YEAR, scope: "INSTITUTION", scopeRefId: "", name: "N".repeat(121) })).toEqual({ name: "too-long" });
    expect(validatePriceList({ academicYearId: YEAR, scope: "INSTITUTION", scopeRefId: "", name: "2027\u0000" })).toEqual({ name: "format" });
    expect(validatePriceList({ academicYearId: YEAR, scope: "GRADE", scopeRefId: GRADE, name: "Primero" })).toEqual({});
  });

  it("builds the archive or reactivation from the list and the version it was read at, changing only the status", () => {
    expect(statusBodyOf(priceList({ name: "2027", version: 5 }), "ARCHIVED")).toEqual({ version: 5, name: "2027", status: "ARCHIVED" });
    expect(statusBodyOf(priceList({ name: "2027", version: 6, status: "ARCHIVED" }), "ACTIVE")).toEqual({ version: 6, name: "2027", status: "ACTIVE" });
  });
});
