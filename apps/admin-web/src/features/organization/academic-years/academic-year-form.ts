import {
  CreateAcademicYearRequest,
  UpdateAcademicYearRequest,
  type AcademicYearResponseOutput,
  type CreateAcademicYearRequest as CreateBody,
  type UpdateAcademicYearRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ApiResult } from "../../../shared/api/http.ts";
import { createAcademicYear, updateAcademicYear } from "../../../shared/api/organization-operations.ts";
import { reasonsFromSchema, requiredReasons } from "../../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../../shared/forms/use-access-form.ts";
import { activeValueOf } from "../organization-form.ts";

export type AcademicYear = AcademicYearResponseOutput;
export type YearField = "name" | "startDate" | "endDate" | "status" | "isActive";

export const YEAR_CONTROL_IDS: Readonly<Record<YearField, string>> = {
  name: "academic-year-name",
  startDate: "academic-year-start",
  endDate: "academic-year-end",
  status: "academic-year-status",
  isActive: "academic-year-active",
};

const FIELDS = Object.keys(YEAR_CONTROL_IDS) as YearField[];

export type YearValues = Readonly<Record<YearField, string>>;

export const initialYearValues = (row?: AcademicYear): YearValues => ({
  name: row?.name ?? "",
  startDate: row?.startDate ?? "",
  endDate: row?.endDate ?? "",
  status: row?.status ?? "PLANNED",
  isActive: activeValueOf(row?.isActive ?? true),
});

/** What goes to the API: a new year has no `isActive`, an edit replaces every editable field and includes it. */
export type YearSave = { readonly mode: "create"; readonly body: CreateBody } | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

/** The editable fields of a year as the update takes them; `isActive` is what the call changes. */
export const updateBodyOf = (row: AcademicYear, isActive: boolean): UpdateBody => ({
  name: row.name,
  startDate: row.startDate,
  endDate: row.endDate,
  status: row.status,
  isActive,
});

export function saveOf(row: AcademicYear | undefined, values: YearValues): YearSave {
  if (row === undefined) {
    return { mode: "create", body: { name: values.name, startDate: values.startDate, endDate: values.endDate, status: values.status as "PLANNED" | "ACTIVE" } };
  }
  return {
    mode: "update",
    id: row.id,
    body: {
      name: values.name,
      startDate: values.startDate,
      endDate: values.endDate,
      status: values.status as AcademicYear["status"],
      isActive: values.isActive === "true",
    },
  };
}

/**
 * The reasons a year's form is refused before sending, from the generated request and the three things it does not
 * say: a name is not blank, a date is not empty, and the end is after the start. The server decides the rest.
 */
export function validateYear(row: AcademicYear | undefined) {
  return (values: YearValues): Partial<Record<YearField, FieldReason>> => {
    const { body } = saveOf(row, values);
    const schema = row === undefined ? CreateAcademicYearRequest : UpdateAcademicYearRequest;
    const reasons: Partial<Record<YearField, FieldReason>> = {
      ...reasonsFromSchema(schema, body, FIELDS),
      ...requiredReasons(values, ["name", "startDate", "endDate"]),
    };
    // ISO dates order as text, so this compares days and nothing else.
    if (reasons.startDate === undefined && reasons.endDate === undefined && values.endDate <= values.startDate) reasons.endDate = "end-after-start";
    return reasons;
  };
}

export function writeYear(token: string, save: YearSave): Promise<ApiResult<AcademicYear>> {
  return save.mode === "create" ? createAcademicYear(token, save.body) : updateAcademicYear(token, save.id, save.body);
}
