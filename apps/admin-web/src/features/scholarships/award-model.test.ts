import { describe, expect, it } from "vitest";
import { awardReasonOf, initialAwardValues, requestBodyOf, validateAward, type AwardValues } from "./award-model.ts";

// The model of the request of an award and of the reason of a decision: what is refused before anything is sent. Whether the validity is inside the
// academic year is the server's to decide (it holds the dates of the year), and the reason is judged on its characters, 20 to 500 once stripped.
const ID = "00000000-0000-4000-8000-0000000000b1";
const values = (override: Partial<AwardValues> = {}): AwardValues => ({
  studentId: ID,
  scholarshipId: "00000000-0000-4000-8000-0000000e0001",
  academicYearId: "00000000-0000-4000-8000-000000000a01",
  validFrom: "2027-01-01",
  validTo: "2027-12-31",
  ...override,
});

describe("the request of an award", () => {
  it("accepts a validity of one day and a whole year", () => {
    expect(validateAward(values())).toEqual({});
    expect(validateAward(values({ validFrom: "2027-05-05", validTo: "2027-05-05" }))).toEqual({});
  });

  it("starts with the student of the address when there is one, and nothing else", () => {
    expect(initialAwardValues(ID)).toMatchObject({ studentId: ID, scholarshipId: "", validFrom: "" });
    expect(initialAwardValues().studentId).toBe("");
  });

  it("sends each value as the person chose it", () => {
    expect(requestBodyOf(values())).toEqual({
      studentId: ID,
      scholarshipId: "00000000-0000-4000-8000-0000000e0001",
      academicYearId: "00000000-0000-4000-8000-000000000a01",
      validFrom: "2027-01-01",
      validTo: "2027-12-31",
    });
  });

  it("refuses every field left empty", () => {
    expect(validateAward(initialAwardValues())).toEqual({
      studentId: "required",
      scholarshipId: "required",
      academicYearId: "required",
      validFrom: "required",
      validTo: "required",
    });
  });

  it("refuses an end before the start, and says it on the end", () => {
    expect(validateAward(values({ validFrom: "2027-06-01", validTo: "2027-05-31" }))).toEqual({ validTo: "validity-order" });
  });

  it("refuses a date that is not a day", () => {
    expect(validateAward(values({ validFrom: "01/01/2027" })).validFrom).toBe("date-range");
    expect(validateAward(values({ validTo: "2027-1-1" })).validTo).toBe("date-range");
  });
});

describe("the reason of a rejection or a revocation", () => {
  it.each([
    ["", "required"],
    ["a".repeat(19), "cash-reason-length"],
    ["a".repeat(501), "cash-reason-length"],
    ["Una línea\ncon un salto de línea que pasa de veinte", "cash-reason-control"],
  ])("refuses %j as %s", (text, reason) => {
    expect(awardReasonOf(text)).toBe(reason);
  });

  it("accepts 20 and 500 characters, and counts them once stripped of the spaces at the borders", () => {
    expect(awardReasonOf("a".repeat(20))).toBeUndefined();
    expect(awardReasonOf("a".repeat(500))).toBeUndefined();
    expect(awardReasonOf(`   ${"a".repeat(19)}   `)).toBe("cash-reason-length");
  });
});
