import {
  SignReconciliationRequest,
  signReconciliationRequestOfficialEnrollmentCountMax,
  signReconciliationRequestOfficialReferenceMax,
} from "@confia/contracts/admin";
import type { AdminKey } from "../../i18n/resources.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import type { AccessFailure } from "../../shared/forms/access-failure.ts";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";
import { failureOf, loadFailureOf, type LoadFailure } from "../organization/organization-failure.ts";
import { wholeNumberOf } from "../organization/organization-form.ts";

/** The fields of the form of the acta. The year and the official total are chosen or typed; the server counts the rest. */
export type ReconciliationField = "academicYearId" | "officialReference" | "officialEnrollmentCount";

/** The fixed `id` of each control: the error summary links to them. */
export const RECONCILIATION_CONTROL_IDS: Readonly<Record<ReconciliationField, string>> = {
  academicYearId: "reconciliation-year",
  officialReference: "reconciliation-reference",
  officialEnrollmentCount: "reconciliation-official-count",
};

export type ReconciliationValues = Readonly<Record<ReconciliationField, string>>;

export const initialReconciliationValues = (): ReconciliationValues => ({ academicYearId: "", officialReference: "", officialEnrollmentCount: "" });

/** The longest reference and the largest official total the contract admits (from the generated schema, shown in the help texts). */
export const MAX_REFERENCE_LENGTH = signReconciliationRequestOfficialReferenceMax;
export const MAX_OFFICIAL_COUNT = signReconciliationRequestOfficialEnrollmentCountMax;

/** What the schema checks. The total is a number when it is written as a whole one, and the text itself otherwise, so the schema refuses it. */
const candidateOf = (values: ReconciliationValues): unknown => ({
  academicYearId: values.academicYearId,
  officialReference: values.officialReference,
  officialEnrollmentCount: wholeNumberOf(values.officialEnrollmentCount),
});

/** The body of the signing. Only valid values reach it; the server counts everything else. */
export const reconciliationBodyOf = (values: ReconciliationValues): SignReconciliationRequest => ({
  academicYearId: values.academicYearId,
  officialReference: values.officialReference,
  officialEnrollmentCount: Number(values.officialEnrollmentCount),
});

/**
 * The first refusal of each field, from the generated schema (a convenience: the server decides). A total that is not a whole
 * number between 0 and 1 000 000 has one message for every way of not being one. An empty year, reference or total is `required`.
 */
export function validateReconciliation(values: ReconciliationValues): Partial<Record<ReconciliationField, FieldReason>> {
  const fields = ["academicYearId", "officialReference", "officialEnrollmentCount"] as const;
  return {
    ...reasonsFromSchema(SignReconciliationRequest, candidateOf(values), fields, { officialEnrollmentCount: "whole" }),
    ...requiredReasons(values, fields),
  };
}

/** The official total as a number once what was typed is a valid one, and nothing while it is not. */
export function officialCountOf(text: string): number | undefined {
  const trimmed = text.trim();
  if (!/^\d{1,9}$/.test(trimmed)) return undefined;
  const count = Number(trimmed);
  return count <= MAX_OFFICIAL_COUNT ? count : undefined;
}

export type DifferenceKind = "more" | "fewer" | "same";

/**
 * Active enrollments minus the official total: a plain whole-number count (never money), so the screen shows the preview's
 * difference as soon as the person types the total. A signed acta shows the server's `difference` instead.
 */
export const differenceOf = (enrollmentsActive: number, officialCount: number): number => enrollmentsActive - officialCount;

export const differenceKindOf = (difference: number): DifferenceKind => (difference > 0 ? "more" : difference < 0 ? "fewer" : "same");

/** What the signing answered wrong: a year that is not the institution's has a text of its own; the rest is the general case. */
export function signFailureOf(error: unknown): AccessFailure {
  if (error instanceof ApiFailure && error.result.kind === "problem" && error.result.problem.code === "resource-not-found") {
    const { traceId } = error.result.problem;
    return { kind: "message", key: "imports.reconciliation.errors.yearNotFound", ...(traceId === undefined ? {} : { traceId }) };
  }
  return failureOf(error);
}

/** The text of a failed preview: the year that is not the institution's has a text of its own; the rest is the general case. */
export function previewFailureOf(error: unknown): LoadFailure {
  if (error instanceof ApiFailure && error.result.kind === "problem" && error.result.problem.code === "resource-not-found") {
    const { traceId } = error.result.problem;
    return { key: "imports.reconciliation.errors.yearNotFound" satisfies AdminKey, ...(traceId === undefined ? {} : { traceId }) };
  }
  return loadFailureOf(error);
}
