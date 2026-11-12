import type { AdminKey } from "../../i18n/resources.ts";
import type { ProblemCode } from "./problem.ts";

/**
 * The catalog key that says what happened for each code (design.md D7 and section 7). The record is
 * exhaustive by construction: a code added to `ProblemCode` without a key here does not compile (AW48).
 * A code with no text of its own shows the generic message; the screen adds the trace id next to it.
 * Where a screen has a better text for its own case (the recovery code, for one), it picks that key.
 */
export const PROBLEM_KEYS: Readonly<Record<ProblemCode | "unknown", AdminKey>> = {
  "authentication-failed": "problem.authenticationFailed",
  "second-factor-invalid": "problem.secondFactorInvalid",
  "session-expired": "notice.sessionExpired",
  "csrf-invalid": "problem.unexpected",
  "mfa-already-enrolled": "notice.alreadyEnrolled",
  "validation-failed": "problem.notProcessed",
  "unsupported-media-type": "problem.unexpected",
  "too-many-requests": "problem.rateLimited",
  "capacity-exceeded": "problem.capacityExceeded",
  "internal-error": "problem.unexpected",
  "authentication-required": "problem.unexpected",
  "token-invalid": "notice.verificationInterrupted",
  "token-expired": "notice.verificationInterrupted",
  forbidden: "problem.forbidden",
  "resource-not-found": "problem.notFound",
  "academic-year-overlap": "problem.academicYearOverlap",
  "academic-year-already-active": "problem.academicYearAlreadyActive",
  "academic-year-closed": "problem.academicYearClosed",
  "duplicate-name": "problem.duplicateName",
  "inactive-reference": "problem.inactiveReference",
  "duplicate-student-code": "problem.duplicateStudentCode",
  "duplicate-national-id": "problem.duplicateNationalId",
  "student-not-active": "problem.studentNotActive",
  "section-full": "problem.sectionFull",
  "duplicate-active-enrollment": "problem.duplicateActiveEnrollment",
  "invalid-enrollment-state": "problem.invalidEnrollmentState",
  "transfer-year-mismatch": "problem.transferYearMismatch",
  "invalid-responsibility-split": "problem.invalidResponsibilitySplit",
  "guardian-not-active": "problem.guardianNotActive",
  unknown: "problem.unexpected",
};
