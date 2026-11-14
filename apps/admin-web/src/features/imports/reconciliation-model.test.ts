import { describe, expect, it } from "vitest";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import {
  MAX_OFFICIAL_COUNT,
  MAX_REFERENCE_LENGTH,
  differenceKindOf,
  differenceOf,
  initialReconciliationValues,
  officialCountOf,
  previewFailureOf,
  reconciliationBodyOf,
  signFailureOf,
  validateReconciliation,
} from "./reconciliation-model.ts";

const YEAR = "11111111-1111-4111-8111-111111111111";
const valid = { academicYearId: YEAR, officialReference: "Oficio 1", officialEnrollmentCount: "120" };
const problemFailure = (code: string, status: number, traceId?: string) =>
  new ApiFailure({ kind: "problem", problem: { status, code, errors: [], ...(traceId === undefined ? {} : { traceId }) } as never });

describe("the validation of the form of the acta", () => {
  it("accepts a year, a reference and a whole official total", () => {
    expect(validateReconciliation(valid)).toEqual({});
  });

  it("asks for each field that is empty, and takes a total of zero as a value", () => {
    expect(validateReconciliation(initialReconciliationValues())).toEqual({
      academicYearId: "required",
      officialReference: "required",
      officialEnrollmentCount: "required",
    });
    expect(validateReconciliation({ ...valid, officialEnrollmentCount: "0" })).toEqual({});
  });

  it("takes a reference of only spaces as empty, of 1 and of the longest length as valid, and one character more as too long", () => {
    expect(validateReconciliation({ ...valid, officialReference: "   " }).officialReference).toBe("required");
    expect(validateReconciliation({ ...valid, officialReference: "7" })).toEqual({});
    expect(validateReconciliation({ ...valid, officialReference: "1".repeat(MAX_REFERENCE_LENGTH) })).toEqual({});
    expect(validateReconciliation({ ...valid, officialReference: "1".repeat(MAX_REFERENCE_LENGTH + 1) }).officialReference).toBe("too-long");
  });

  it("refuses a reference with a control character", () => {
    expect(validateReconciliation({ ...valid, officialReference: "Oficio\u0007" }).officialReference).toBe("format");
  });

  it.each(["Juan Perez", "Oficio", "Oficio 1‮", "Oficio <b>1</b>", "Oficio 1;"])(
    "refuses the reference %s: it is the number of a document, not a name or a symbol",
    (reference) => {
      expect(validateReconciliation({ ...valid, officialReference: reference }).officialReference).toBe("format");
    },
  );

  it.each(["Oficio 123-2026", "No. 45/2026 #3", "OF_2026-0045"])("accepts the reference %s", (reference) => {
    expect(validateReconciliation({ ...valid, officialReference: reference })).toEqual({});
  });

  it.each(["-1", "1.5", "12a", "1 000", "-0"])("refuses the total %s with the message of a whole number", (total) => {
    expect(validateReconciliation({ ...valid, officialEnrollmentCount: total }).officialEnrollmentCount).toBe("whole");
  });

  it("accepts the largest total and refuses the next", () => {
    expect(validateReconciliation({ ...valid, officialEnrollmentCount: String(MAX_OFFICIAL_COUNT) })).toEqual({});
    expect(validateReconciliation({ ...valid, officialEnrollmentCount: String(MAX_OFFICIAL_COUNT + 1) }).officialEnrollmentCount).toBe("whole");
  });

  it("refuses a year that is not an identifier", () => {
    expect(validateReconciliation({ ...valid, academicYearId: "2026" }).academicYearId).toBe("format");
  });
});

describe("the body of the signing", () => {
  it("sends the year, the reference and the total as a number, and nothing the server counts", () => {
    expect(reconciliationBodyOf(valid)).toEqual({ academicYearId: YEAR, officialReference: "Oficio 1", officialEnrollmentCount: 120 });
  });
});

describe("the official total of the preview", () => {
  it("is a number only once what was typed is a whole one within the limit", () => {
    expect(officialCountOf("120")).toBe(120);
    expect(officialCountOf(" 0 ")).toBe(0);
    expect(officialCountOf("")).toBeUndefined();
    expect(officialCountOf("-3")).toBeUndefined();
    expect(officialCountOf("1.5")).toBeUndefined();
    expect(officialCountOf("abc")).toBeUndefined();
    expect(officialCountOf(String(MAX_OFFICIAL_COUNT + 1))).toBeUndefined();
  });
});

describe("the difference", () => {
  it("is the active enrollments minus the official total, and is named more, fewer or the same", () => {
    expect(differenceOf(118, 120)).toBe(-2);
    expect(differenceOf(125, 120)).toBe(5);
    expect(differenceOf(0, 0)).toBe(0);
    expect(differenceKindOf(5)).toBe("more");
    expect(differenceKindOf(-2)).toBe("fewer");
    expect(differenceKindOf(0)).toBe("same");
  });
});

describe("what a failed request says", () => {
  it("gives a year that is not of the institution its own text, with the trace id", () => {
    expect(signFailureOf(problemFailure("resource-not-found", 404, "t-1"))).toEqual({
      kind: "message",
      key: "imports.reconciliation.errors.yearNotFound",
      traceId: "t-1",
    });
    expect(previewFailureOf(problemFailure("resource-not-found", 404))).toEqual({ key: "imports.reconciliation.errors.yearNotFound" });
  });

  it("leaves the rest to the general case: a 403 is no permission, a 400 goes to its fields", () => {
    expect(signFailureOf(problemFailure("forbidden", 403))).toMatchObject({ kind: "message", key: "problem.forbidden" });
    expect(previewFailureOf(problemFailure("forbidden", 403))).toMatchObject({ key: "problem.forbidden" });
    const refused = new ApiFailure({
      kind: "problem",
      problem: { status: 400, code: "validation-failed", errors: [{ field: "officialReference", reason: "size" }] } as never,
    });
    expect(signFailureOf(refused)).toEqual({ kind: "fields", errors: [{ field: "officialReference", reason: "size" }] });
    expect(signFailureOf(new Error("boom"))).toEqual({ kind: "unexpected" });
  });
});
