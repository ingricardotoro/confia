import {
  CreateSectionRequest,
  UpdateSectionRequest,
  type CreateSectionRequest as CreateBody,
  type SectionResponseOutput,
  type UpdateSectionRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ApiResult } from "../../../shared/api/http.ts";
import { createSection, updateSection } from "../../../shared/api/organization-operations.ts";
import { reasonsFromSchema, requiredReasons } from "../../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../../shared/forms/use-access-form.ts";
import { activeValueOf, wholeNumberOf } from "../organization-form.ts";

export type Section = SectionResponseOutput;
export type SectionField = "academicYearId" | "gradeId" | "name" | "capacity" | "isActive";

export const SECTION_CONTROL_IDS: Readonly<Record<SectionField, string>> = {
  academicYearId: "section-academic-year",
  gradeId: "section-grade",
  name: "section-name",
  capacity: "section-capacity",
  isActive: "section-active",
};

const FIELDS = Object.keys(SECTION_CONTROL_IDS) as SectionField[];

export type SectionValues = Readonly<Record<SectionField, string>>;

/** The filters of the list, when set, are what a new section starts with. */
export const initialSectionValues = (row?: Section, defaults: { academicYearId?: string; gradeId?: string } = {}): SectionValues => ({
  academicYearId: row?.academicYearId ?? defaults.academicYearId ?? "",
  gradeId: row?.gradeId ?? defaults.gradeId ?? "",
  name: row?.name ?? "",
  capacity: row?.capacity == null ? "" : String(row.capacity),
  isActive: activeValueOf(row?.isActive ?? true),
});

/** The year and the grade are fixed once the section exists, so only a new section sends them. */
export type SectionSave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

/** No capacity means no limit: the field is left out, and the update takes the absence as "none". */
export const updateBodyOf = (row: Section, isActive: boolean): UpdateBody => ({
  name: row.name,
  isActive,
  ...(row.capacity == null ? {} : { capacity: row.capacity }),
});

/** The candidate body: a whole number typed as text becomes a number, and anything else stays text for the schema to refuse. */
function candidateOf(row: Section | undefined, values: SectionValues) {
  const capacity = values.capacity.trim() === "" ? {} : { capacity: wholeNumberOf(values.capacity.trim()) };
  return row === undefined
    ? { academicYearId: values.academicYearId, gradeId: values.gradeId, name: values.name, ...capacity }
    : { name: values.name, isActive: values.isActive === "true", ...capacity };
}

export function saveOf(row: Section | undefined, values: SectionValues): SectionSave {
  const body = candidateOf(row, values);
  return row === undefined ? { mode: "create", body: body as CreateBody } : { mode: "update", id: row.id, body: body as UpdateBody };
}

/** The reasons the form is refused before sending: the generated request, what is empty or blank, and a capacity that is not a whole number above zero. */
export function validateSection(row: Section | undefined) {
  return (values: SectionValues): Partial<Record<SectionField, FieldReason>> => ({
    ...reasonsFromSchema(row === undefined ? CreateSectionRequest : UpdateSectionRequest, candidateOf(row, values), FIELDS, {
      capacity: "positive-whole",
    }),
    ...requiredReasons(values, row === undefined ? ["academicYearId", "gradeId", "name"] : ["name"]),
  });
}

export function writeSection(token: string, save: SectionSave): Promise<ApiResult<Section>> {
  return save.mode === "create" ? createSection(token, save.body) : updateSection(token, save.id, save.body);
}
