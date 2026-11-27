import { describe, expect, it } from "vitest";
import { assignmentRequestBodyOf, initialAssignmentValues, validateAssignment, type AssignmentValues } from "./assignment-model.ts";

// The form model of the request of an assignment: what is sent and what is refused before anything is sent. Whether the validity is inside the one of
// the discount is the server's to decide.
const STUDENT = "00000000-0000-4000-8000-0000000000b1";
const DISCOUNT = "00000000-0000-4000-8000-0000000e0101";
const values = (override: Partial<AssignmentValues> = {}): AssignmentValues => ({
  studentId: STUDENT,
  discountId: DISCOUNT,
  validFrom: "2027-01-01",
  validTo: "2027-12-31",
  ...override,
});

describe("the request of an assignment", () => {
  it("sends the four values as the person chose them", () => {
    expect(assignmentRequestBodyOf(values())).toEqual({ studentId: STUDENT, discountId: DISCOUNT, validFrom: "2027-01-01", validTo: "2027-12-31" });
  });

  it("starts empty, or with the student the list is already pointed at", () => {
    expect(initialAssignmentValues()).toEqual({ studentId: "", discountId: "", validFrom: "", validTo: "" });
    expect(initialAssignmentValues(STUDENT).studentId).toBe(STUDENT);
  });

  it("accepts a complete request, and a validity of one day because both ends are included", () => {
    expect(validateAssignment(values())).toEqual({});
    expect(validateAssignment(values({ validFrom: "2027-03-01", validTo: "2027-03-01" }))).toEqual({});
  });

  it("requires every value", () => {
    expect(validateAssignment(initialAssignmentValues())).toEqual({ studentId: "required", discountId: "required", validFrom: "required", validTo: "required" });
  });

  it("refuses an end before the start and a date that is not a day", () => {
    expect(validateAssignment(values({ validFrom: "2027-03-02", validTo: "2027-03-01" })).validTo).toBe("validity-order");
    expect(validateAssignment(values({ validFrom: "01/01/2027" })).validFrom).toBe("date-range");
    expect(validateAssignment(values({ validTo: "mañana" })).validTo).toBe("date-range");
  });
});
