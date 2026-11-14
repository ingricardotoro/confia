import {
  EnrollStudentRequest,
  ExitEnrollmentRequest,
  TransferEnrollmentRequest,
  type EnrollStudentRequest as EnrollBody,
  type ExitEnrollmentRequest as ExitBody,
  type TransferEnrollmentRequest as TransferBody,
} from "@confia/contracts/admin";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";
import { todayIso, type ExitKind } from "./enrollment-model.ts";

/** The reason of a withdrawal or a transfer is 3 to 500 characters once trimmed; the generated schema only bounds the raw text. */
const MIN_REASON = 3;
const MAX_REASON = 500;

const reasonLengthOf = (text: string): FieldReason | undefined =>
  text !== "" && (text.length < MIN_REASON || text.length > MAX_REASON) ? "reason-length" : undefined;

// --- Enroll ---------------------------------------------------------------------------------------------------------

export type EnrollField = "studentId" | "sectionId" | "enrolledOn";

/** The id of the student control is the id of the search box: the error summary links to it. */
export const ENROLL_CONTROL_IDS: Readonly<Record<EnrollField, string>> = {
  studentId: "enrollment-student",
  sectionId: "enrollment-section",
  enrolledOn: "enrollment-date",
};

export type EnrollValues = Readonly<Record<EnrollField, string>>;

export const initialEnrollValues = (): EnrollValues => ({ studentId: "", sectionId: "", enrolledOn: todayIso() });

/** The year is the section's, so the body has no year. */
export const enrollBodyOf = (values: EnrollValues): EnrollBody => ({
  studentId: values.studentId,
  sectionId: values.sectionId,
  enrolledOn: values.enrolledOn,
});

export function validateEnroll(values: EnrollValues): Partial<Record<EnrollField, FieldReason>> {
  return {
    ...reasonsFromSchema(EnrollStudentRequest, enrollBodyOf(values), ["studentId", "sectionId", "enrolledOn"]),
    ...requiredReasons(values, ["studentId", "sectionId", "enrolledOn"]),
  };
}

// --- Transfer -------------------------------------------------------------------------------------------------------

export type TransferField = "toSectionId" | "transferredOn" | "reason";

export const TRANSFER_CONTROL_IDS: Readonly<Record<TransferField, string>> = {
  toSectionId: "transfer-section",
  transferredOn: "transfer-date",
  reason: "transfer-reason",
};

export type TransferValues = Readonly<Record<TransferField, string>>;

export const initialTransferValues = (): TransferValues => ({ toSectionId: "", transferredOn: todayIso(), reason: "" });

/** No reason is no `reason`: the server takes an empty one as none, but it is not sent. */
export const transferBodyOf = (values: TransferValues): TransferBody => ({
  toSectionId: values.toSectionId,
  transferredOn: values.transferredOn,
  ...(values.reason === "" ? {} : { reason: values.reason }),
});

export function validateTransfer(values: TransferValues): Partial<Record<TransferField, FieldReason>> {
  const reasons: Partial<Record<TransferField, FieldReason>> = {
    ...reasonsFromSchema(TransferEnrollmentRequest, transferBodyOf(values), ["toSectionId", "transferredOn", "reason"]),
    ...requiredReasons(values, ["toSectionId", "transferredOn"]),
  };
  const length = reasonLengthOf(values.reason);
  if (length !== undefined) reasons.reason = length;
  return reasons;
}

// --- Exit -----------------------------------------------------------------------------------------------------------

export type ExitField = "status" | "withdrawnOn" | "withdrawalReason";

export const EXIT_CONTROL_IDS: Readonly<Record<ExitField, string>> = {
  status: "exit-kind",
  withdrawnOn: "exit-date",
  withdrawalReason: "exit-reason",
};

export type ExitValues = Readonly<Record<ExitField, string>>;

export const EXIT_KINDS: readonly ExitKind[] = ["WITHDRAWN", "TRANSFERRED", "GRADUATED"];

export const initialExitValues = (): ExitValues => ({ status: "WITHDRAWN", withdrawnOn: todayIso(), withdrawalReason: "" });

/** A graduation may go without a reason; a withdrawal and a transfer to another institution may not. */
export const reasonRequiredFor = (status: string): boolean => status !== "GRADUATED";

export const exitBodyOf = (values: ExitValues): ExitBody => ({
  status: values.status as ExitKind,
  withdrawnOn: values.withdrawnOn,
  ...(values.withdrawalReason === "" ? {} : { withdrawalReason: values.withdrawalReason }),
});

export function validateExit(values: ExitValues): Partial<Record<ExitField, FieldReason>> {
  const reasons: Partial<Record<ExitField, FieldReason>> = {
    ...reasonsFromSchema(ExitEnrollmentRequest, exitBodyOf(values), ["status", "withdrawnOn", "withdrawalReason"]),
    ...requiredReasons(values, ["status", "withdrawnOn"]),
  };
  if (reasonRequiredFor(values.status) && values.withdrawalReason === "") reasons.withdrawalReason = "required";
  else {
    const length = reasonLengthOf(values.withdrawalReason);
    if (length !== undefined) reasons.withdrawalReason = length;
  }
  return reasons;
}
