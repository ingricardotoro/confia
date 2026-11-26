import {
  CreateScholarshipRequest,
  UpdateScholarshipRequest,
  type CreateScholarshipRequest as CreateBody,
  type UpdateScholarshipRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ApiResult } from "../../shared/api/http.ts";
import { createScholarship, updateScholarship, type BenefitType, type Scholarship } from "../../shared/api/scholarships-operations.ts";
import { amountReasonOf } from "../../shared/forms/text-rules.ts";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";

export type { Scholarship } from "../../shared/api/scholarships-operations.ts";

export type ScholarshipField = "code" | "name" | "benefitType" | "percent" | "fixedAmount" | "periodCap" | "conceptIds";

export const SCHOLARSHIP_CONTROL_IDS: Readonly<Record<ScholarshipField, string>> = {
  code: "scholarship-code",
  name: "scholarship-name",
  benefitType: "scholarship-benefit-type",
  percent: "scholarship-percent",
  fixedAmount: "scholarship-fixed-amount",
  periodCap: "scholarship-period-cap",
  conceptIds: "scholarship-concepts",
};

const FIELDS = Object.keys(SCHOLARSHIP_CONTROL_IDS) as ScholarshipField[];

export type ScholarshipValues = Readonly<Record<ScholarshipField, string>>;

/** The currency of the amounts of a scholarship: the one of the institution, which today is the lempira. The server names it too. */
export const SCHOLARSHIP_CURRENCY = "HNL";
export const SCHOLARSHIP_NAME_MAX = 120;

/** The concepts a form holds are one text, the ids joined by commas (a form holds text); a scholarship has between one and fifty. */
export const conceptIdsOf = (value: string): string[] => (value === "" ? [] : value.split(","));
export const joinConceptIds = (ids: readonly string[]): string => ids.join(",");

export const initialScholarshipValues = (row?: Scholarship): ScholarshipValues => ({
  code: row?.code ?? "",
  name: row?.name ?? "",
  benefitType: row?.benefitType ?? "",
  percent: row?.percent ?? "",
  fixedAmount: row?.fixedAmount?.amount ?? "",
  periodCap: row?.periodCap?.amount ?? "",
  conceptIds: joinConceptIds(row?.concepts.map((concept) => concept.id) ?? []),
});

/** A new scholarship has a code; an edit has the version it was read at and no code, because the code never changes. */
export type ScholarshipSave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

const moneyOf = (amount: string) => ({ amount, currency: SCHOLARSHIP_CURRENCY });

/**
 * The body of the form. The value that does not belong to the type of benefit is dropped (a percentage scholarship has no fixed amount), and an empty
 * cap is no cap. An empty type is refused by the schema before anything is sent: the cast only lets the schema judge it.
 */
export function saveOf(row: Scholarship | undefined, values: ScholarshipValues): ScholarshipSave {
  const benefitType = values.benefitType as BenefitType;
  const common = {
    name: values.name,
    benefitType,
    conceptIds: conceptIdsOf(values.conceptIds),
    ...(benefitType === "PERCENTAGE" ? { percent: values.percent } : { fixedAmount: moneyOf(values.fixedAmount) }),
    ...(values.periodCap === "" ? {} : { periodCap: moneyOf(values.periodCap) }),
  };
  if (row === undefined) return { mode: "create", body: { code: values.code, ...common } };
  return { mode: "update", id: row.id, body: { version: row.version, ...common } };
}

const CODE_FORMAT = /^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$/;
const PERCENT_FORMAT = /^\d{1,3}(\.\d{1,2})?$/;

/** How many characters the server counts: code points, so an emoji counts once. */
const lengthOf = (text: string): number => Array.from(text.trim()).length;

/**
 * The reasons the form is refused before sending. The server decides the rest (a code already used, a value that is locked, a concept that is
 * inactive): this only spares the person the refusals it can see, judged on the characters. A percentage is a number of points, never an amount.
 */
export function validateScholarship(row: Scholarship | undefined) {
  return (values: ScholarshipValues): Partial<Record<ScholarshipField, FieldReason>> => {
    const own: Partial<Record<ScholarshipField, FieldReason>> = {};
    if (row === undefined) {
      if (values.code === "") own.code = "required";
      else if (!CODE_FORMAT.test(values.code)) own.code = "code-format";
    }
    if (values.name.trim() === "") own.name = "required";
    else if (lengthOf(values.name) > SCHOLARSHIP_NAME_MAX) own.name = "too-long";
    if (values.benefitType === "") own.benefitType = "required";
    if (values.benefitType === "PERCENTAGE") {
      if (values.percent === "") own.percent = "required";
      else if (!PERCENT_FORMAT.test(values.percent)) own.percent = "percent-format";
      else if (Number(values.percent) <= 0 || Number(values.percent) > 100) own.percent = "percent-range";
    }
    if (values.benefitType === "FIXED_AMOUNT") {
      const reason = amountReasonOf(values.fixedAmount, true);
      if (reason !== undefined) own.fixedAmount = reason;
    }
    if (values.periodCap !== "") {
      const reason = amountReasonOf(values.periodCap, true);
      if (reason !== undefined) own.periodCap = reason;
    }
    if (conceptIdsOf(values.conceptIds).length === 0) own.conceptIds = "required";
    const schema = reasonsFromSchema(row === undefined ? CreateScholarshipRequest : UpdateScholarshipRequest, saveOf(row, values).body, FIELDS);
    return { ...schema, ...requiredReasons(values, ["name"]), ...own };
  };
}

export function writeScholarship(token: string, save: ScholarshipSave): Promise<ApiResult<Scholarship>> {
  return save.mode === "create" ? createScholarship(token, save.body) : updateScholarship(token, save.id, save.body);
}
