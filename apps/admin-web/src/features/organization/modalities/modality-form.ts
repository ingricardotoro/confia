import {
  CreateModalityRequest,
  UpdateModalityRequest,
  type CreateModalityRequest as CreateBody,
  type ModalityResponseOutput,
  type UpdateModalityRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ApiResult } from "../../../shared/api/http.ts";
import { createModality, updateModality } from "../../../shared/api/organization-operations.ts";
import { reasonsFromSchema, requiredReasons } from "../../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../../shared/forms/use-access-form.ts";
import { activeValueOf } from "../organization-form.ts";

export type Modality = ModalityResponseOutput;
export type ModalityField = "code" | "name" | "isActive";

export const MODALITY_CONTROL_IDS: Readonly<Record<ModalityField, string>> = {
  code: "modality-code",
  name: "modality-name",
  isActive: "modality-active",
};

const FIELDS = Object.keys(MODALITY_CONTROL_IDS) as ModalityField[];

export type ModalityValues = Readonly<Record<ModalityField, string>>;

export const initialModalityValues = (row?: Modality): ModalityValues => ({
  code: row?.code ?? "",
  name: row?.name ?? "",
  isActive: activeValueOf(row?.isActive ?? true),
});

/** A new modality has no `isActive`; an edit replaces every editable field and includes it. */
export type ModalitySave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

export const updateBodyOf = (row: Modality, isActive: boolean): UpdateBody => ({ code: row.code, name: row.name, isActive });

export function saveOf(row: Modality | undefined, values: ModalityValues): ModalitySave {
  if (row === undefined) return { mode: "create", body: { code: values.code, name: values.name } };
  return { mode: "update", id: row.id, body: { code: values.code, name: values.name, isActive: values.isActive === "true" } };
}

/** The reasons the form is refused before sending: the generated request, and a code and a name that are not blank. */
export function validateModality(row: Modality | undefined) {
  return (values: ModalityValues): Partial<Record<ModalityField, FieldReason>> => ({
    ...reasonsFromSchema(row === undefined ? CreateModalityRequest : UpdateModalityRequest, saveOf(row, values).body, FIELDS),
    ...requiredReasons(values, ["code", "name"]),
  });
}

export function writeModality(token: string, save: ModalitySave): Promise<ApiResult<Modality>> {
  return save.mode === "create" ? createModality(token, save.body) : updateModality(token, save.id, save.body);
}
