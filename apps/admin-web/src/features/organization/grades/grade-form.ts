import {
  CreateGradeRequest,
  UpdateGradeRequest,
  type CreateGradeRequest as CreateBody,
  type GradeResponseOutput,
  type UpdateGradeRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ApiResult } from "../../../shared/api/http.ts";
import { createGrade, updateGrade } from "../../../shared/api/organization-operations.ts";
import { reasonsFromSchema, requiredReasons } from "../../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../../shared/forms/use-access-form.ts";
import { activeValueOf, wholeNumberOf } from "../organization-form.ts";

export type Grade = GradeResponseOutput;
export type GradeField = "modalityId" | "name" | "ordinal" | "isActive";

export const GRADE_CONTROL_IDS: Readonly<Record<GradeField, string>> = {
  modalityId: "grade-modality",
  name: "grade-name",
  ordinal: "grade-ordinal",
  isActive: "grade-active",
};

const FIELDS = Object.keys(GRADE_CONTROL_IDS) as GradeField[];

export type GradeValues = Readonly<Record<GradeField, string>>;

export const initialGradeValues = (row?: Grade, modalityId?: string): GradeValues => ({
  modalityId: row?.modalityId ?? modalityId ?? "",
  name: row?.name ?? "",
  ordinal: row === undefined ? "" : String(row.ordinal),
  isActive: activeValueOf(row?.isActive ?? true),
});

/** The modality is fixed once the grade exists, so only a new grade sends it. */
export type GradeSave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

export const updateBodyOf = (row: Grade, isActive: boolean): UpdateBody => ({ name: row.name, ordinal: row.ordinal, isActive });

/** The candidate body: a whole number typed as text becomes a number, and anything else stays text for the schema to refuse. */
function candidateOf(row: Grade | undefined, values: GradeValues) {
  const ordinal = wholeNumberOf(values.ordinal);
  return row === undefined
    ? { modalityId: values.modalityId, name: values.name, ordinal }
    : { name: values.name, ordinal, isActive: values.isActive === "true" };
}

export function saveOf(row: Grade | undefined, values: GradeValues): GradeSave {
  const body = candidateOf(row, values);
  return row === undefined
    ? { mode: "create", body: body as CreateBody }
    : { mode: "update", id: row.id, body: body as UpdateBody };
}

/** The reasons the form is refused before sending: the generated request, what is empty or blank, and an order that is not a whole number. */
export function validateGrade(row: Grade | undefined) {
  return (values: GradeValues): Partial<Record<GradeField, FieldReason>> => ({
    ...reasonsFromSchema(row === undefined ? CreateGradeRequest : UpdateGradeRequest, candidateOf(row, values), FIELDS, { ordinal: "whole" }),
    ...requiredReasons(values, row === undefined ? ["modalityId", "name", "ordinal"] : ["name", "ordinal"]),
  });
}

export function writeGrade(token: string, save: GradeSave): Promise<ApiResult<Grade>> {
  return save.mode === "create" ? createGrade(token, save.body) : updateGrade(token, save.id, save.body);
}
