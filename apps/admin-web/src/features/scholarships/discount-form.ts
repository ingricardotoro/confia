import {
  CreateDiscountRequest,
  UpdateDiscountRequest,
  type CreateDiscountRequest as CreateBody,
  type UpdateDiscountRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ApiResult } from "../../shared/api/http.ts";
import { createDiscount, updateDiscount, type BenefitType, type Discount, type DiscountKind } from "../../shared/api/scholarships-operations.ts";
import { amountReasonOf } from "../../shared/forms/text-rules.ts";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";
import { conceptIdsOf, joinConceptIds, SCHOLARSHIP_CURRENCY, SCHOLARSHIP_NAME_MAX } from "./scholarship-form.ts";

export type { Discount } from "../../shared/api/scholarships-operations.ts";

export type DiscountField = "code" | "name" | "kind" | "benefitType" | "percent" | "fixedAmount" | "validFrom" | "validTo" | "stackable" | "conceptIds";

export const DISCOUNT_CONTROL_IDS: Readonly<Record<DiscountField, string>> = {
  code: "discount-code",
  name: "discount-name",
  kind: "discount-kind",
  benefitType: "discount-benefit-type",
  percent: "discount-percent",
  fixedAmount: "discount-fixed-amount",
  validFrom: "discount-valid-from",
  validTo: "discount-valid-to",
  stackable: "discount-stackable",
  conceptIds: "discount-concepts",
};

const FIELDS = Object.keys(DISCOUNT_CONTROL_IDS) as DiscountField[];

export type DiscountValues = Readonly<Record<DiscountField, string>>;

/** A form holds text: the stackable mark is `"true"` or `"false"`. A new discount does not stack until the person says so. */
export const initialDiscountValues = (row?: Discount): DiscountValues => ({
  code: row?.code ?? "",
  name: row?.name ?? "",
  kind: row?.kind ?? "",
  benefitType: row?.benefitType ?? "",
  percent: row?.percent ?? "",
  fixedAmount: row?.fixedAmount?.amount ?? "",
  validFrom: row?.validFrom ?? "",
  validTo: row?.validTo ?? "",
  stackable: row === undefined ? "false" : String(row.stackable),
  conceptIds: joinConceptIds(row?.concepts.map((concept) => concept.id) ?? []),
});

/** A new discount has a code and a class; an edit has the version it was read at and neither, because the code and the class never change. */
export type DiscountSave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

const moneyOf = (amount: string) => ({ amount, currency: SCHOLARSHIP_CURRENCY });

/**
 * The body of the form. The value that does not belong to the type of benefit is dropped (a percentage discount has no fixed amount). An empty type
 * or class is refused by the schema before anything is sent: the cast only lets the schema judge it.
 */
export function discountSaveOf(row: Discount | undefined, values: DiscountValues): DiscountSave {
  const benefitType = values.benefitType as BenefitType;
  const common = {
    name: values.name,
    benefitType,
    conceptIds: conceptIdsOf(values.conceptIds),
    stackable: values.stackable === "true",
    validFrom: values.validFrom,
    validTo: values.validTo,
    ...(benefitType === "PERCENTAGE" ? { percent: values.percent } : { fixedAmount: moneyOf(values.fixedAmount) }),
  };
  if (row === undefined) return { mode: "create", body: { code: values.code, kind: values.kind as DiscountKind, ...common } };
  return { mode: "update", id: row.id, body: { version: row.version, ...common } };
}

const CODE_FORMAT = /^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$/;
const PERCENT_FORMAT = /^\d{1,3}(\.\d{1,2})?$/;
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** How many characters the server counts: code points, so an emoji counts once. */
const lengthOf = (text: string): number => Array.from(text.trim()).length;

/**
 * The reasons the form is refused before sending. The server decides the rest (a code already used, a value or a scope that is locked, a validity that
 * would leave an assignment outside it, a concept that is inactive): this only spares the person the refusals it can see, judged on the characters.
 * A percentage is a number of points, never an amount.
 */
export function validateDiscount(row: Discount | undefined) {
  return (values: DiscountValues): Partial<Record<DiscountField, FieldReason>> => {
    const own: Partial<Record<DiscountField, FieldReason>> = {};
    if (row === undefined) {
      if (values.code === "") own.code = "required";
      else if (!CODE_FORMAT.test(values.code)) own.code = "code-format";
      if (values.kind === "") own.kind = "required";
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
    if (values.validFrom === "") own.validFrom = "required";
    else if (!ISO_DAY.test(values.validFrom)) own.validFrom = "date-range";
    if (values.validTo === "") own.validTo = "required";
    else if (!ISO_DAY.test(values.validTo)) own.validTo = "date-range";
    // Both ends are included, so a one-day validity is allowed: the end only cannot come before the start.
    if (own.validFrom === undefined && own.validTo === undefined && values.validTo < values.validFrom) own.validTo = "validity-order";
    if (conceptIdsOf(values.conceptIds).length === 0) own.conceptIds = "required";
    const schema = reasonsFromSchema(row === undefined ? CreateDiscountRequest : UpdateDiscountRequest, discountSaveOf(row, values).body, FIELDS);
    return { ...schema, ...requiredReasons(values, ["name"]), ...own };
  };
}

export function writeDiscount(token: string, save: DiscountSave): Promise<ApiResult<Discount>> {
  return save.mode === "create" ? createDiscount(token, save.body) : updateDiscount(token, save.id, save.body);
}
