import { RequestDiscountAssignmentBody } from "@confia/contracts/admin";
import { reasonsFromSchema } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";

export type AssignmentField = "studentId" | "discountId" | "validFrom" | "validTo";
export type AssignmentValues = Readonly<Record<AssignmentField, string>>;

export const ASSIGNMENT_CONTROL_IDS: Readonly<Record<AssignmentField, string>> = {
  studentId: "assignment-student",
  discountId: "assignment-discount",
  validFrom: "assignment-valid-from",
  validTo: "assignment-valid-to",
};

const FIELDS = Object.keys(ASSIGNMENT_CONTROL_IDS) as AssignmentField[];
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

export const initialAssignmentValues = (studentId?: string): AssignmentValues => ({ studentId: studentId ?? "", discountId: "", validFrom: "", validTo: "" });

/** The body of the request of an assignment: every value as the person chose it. The server checks that the validity is inside the one of the discount. */
export const assignmentRequestBodyOf = (values: AssignmentValues) => ({
  studentId: values.studentId,
  discountId: values.discountId,
  validFrom: values.validFrom,
  validTo: values.validTo,
});

/**
 * The reasons the request is refused before sending: the generated request, a validity whose dates are days, and an end that is before the start (both
 * days are included, so a one-day validity holds). Whether the validity is inside the one of the discount is the server's to decide.
 */
export function validateAssignment(values: AssignmentValues): Partial<Record<AssignmentField, FieldReason>> {
  const own: Partial<Record<AssignmentField, FieldReason>> = {};
  for (const field of FIELDS) if (values[field] === "") own[field] = "required";
  if (values.validFrom !== "" && !ISO_DAY.test(values.validFrom)) own.validFrom = "date-range";
  if (values.validTo !== "" && !ISO_DAY.test(values.validTo)) own.validTo = "date-range";
  if (own.validFrom === undefined && own.validTo === undefined && values.validFrom !== "" && values.validTo !== "" && values.validTo < values.validFrom) {
    own.validTo = "validity-order";
  }
  return { ...reasonsFromSchema(RequestDiscountAssignmentBody, assignmentRequestBodyOf(values), FIELDS), ...own };
}
