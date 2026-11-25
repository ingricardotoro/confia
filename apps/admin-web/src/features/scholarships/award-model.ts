import { RequestScholarshipAwardBody } from "@confia/contracts/admin";
import { reasonsFromSchema } from "../../shared/forms/schema-reasons.ts";
import { noteReasonOf } from "../../shared/forms/text-rules.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";

/** The bounds of the reason of a rejection or a revocation, as the server holds them (20 to 500 characters once stripped of border spaces). */
export const AWARD_REASON_MIN = 20;
export const AWARD_REASON_MAX = 500;

/** The reason of a rejection or a revocation: required, 20 to 500 characters, and no control characters. */
export const awardReasonOf = (text: string): FieldReason | undefined => noteReasonOf(text, { min: AWARD_REASON_MIN, max: AWARD_REASON_MAX, required: true });

export type AwardField = "studentId" | "scholarshipId" | "academicYearId" | "validFrom" | "validTo";
export type AwardValues = Readonly<Record<AwardField, string>>;

export const AWARD_CONTROL_IDS: Readonly<Record<AwardField, string>> = {
  studentId: "award-student",
  scholarshipId: "award-scholarship",
  academicYearId: "award-academic-year",
  validFrom: "award-valid-from",
  validTo: "award-valid-to",
};

const FIELDS = Object.keys(AWARD_CONTROL_IDS) as AwardField[];
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

export const initialAwardValues = (studentId?: string): AwardValues => ({ studentId: studentId ?? "", scholarshipId: "", academicYearId: "", validFrom: "", validTo: "" });

/** The body of the request of an award: every value as the person chose it. The server checks that the validity is inside the academic year. */
export const requestBodyOf = (values: AwardValues) => ({
  studentId: values.studentId,
  scholarshipId: values.scholarshipId,
  academicYearId: values.academicYearId,
  validFrom: values.validFrom,
  validTo: values.validTo,
});

/**
 * The reasons the request is refused before sending: the generated request, a validity whose dates are days, and an end that is not before the
 * start. Whether the validity is inside the academic year is the server's to decide (it holds the dates of the year).
 */
export function validateAward(values: AwardValues): Partial<Record<AwardField, FieldReason>> {
  const own: Partial<Record<AwardField, FieldReason>> = {};
  for (const field of FIELDS) if (values[field] === "") own[field] = "required";
  if (values.validFrom !== "" && !ISO_DAY.test(values.validFrom)) own.validFrom = "date-range";
  if (values.validTo !== "" && !ISO_DAY.test(values.validTo)) own.validTo = "date-range";
  if (own.validFrom === undefined && own.validTo === undefined && values.validFrom !== "" && values.validTo !== "" && values.validTo < values.validFrom) {
    own.validTo = "validity-order";
  }
  return { ...reasonsFromSchema(RequestScholarshipAwardBody, requestBodyOf(values), FIELDS), ...own };
}
