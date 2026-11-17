import {
  CreatePaymentConceptRequest,
  UpdatePaymentConceptRequest,
  type CreatePaymentConceptRequest as CreateBody,
  type PaymentConceptResponseOutput,
  type UpdatePaymentConceptRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { ApiResult } from "../../shared/api/http.ts";
import { createPaymentConcept, updatePaymentConcept, type PaymentCategory } from "../../shared/api/catalog-operations.ts";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";
import { activeValueOf } from "../../shared/resource-screen/resource-form.ts";

export type PaymentConcept = PaymentConceptResponseOutput;
export type PaymentConceptField = "code" | "name" | "category" | "isRecurring" | "isTaxable" | "taxRateId" | "isActive";

export const PAYMENT_CONCEPT_CONTROL_IDS: Readonly<Record<PaymentConceptField, string>> = {
  code: "payment-concept-code",
  name: "payment-concept-name",
  category: "payment-concept-category",
  isRecurring: "payment-concept-recurring",
  isTaxable: "payment-concept-taxable",
  taxRateId: "payment-concept-tax-rate",
  isActive: "payment-concept-active",
};

const FIELDS = Object.keys(PAYMENT_CONCEPT_CONTROL_IDS) as PaymentConceptField[];

export type PaymentConceptValues = Readonly<Record<PaymentConceptField, string>>;

export const initialPaymentConceptValues = (row?: PaymentConcept): PaymentConceptValues => ({
  code: row?.code ?? "",
  name: row?.name ?? "",
  category: row?.category ?? "",
  isRecurring: activeValueOf(row?.isRecurring ?? false),
  isTaxable: activeValueOf(row?.isTaxable ?? false),
  taxRateId: row?.taxRateId ?? "",
  isActive: activeValueOf(row?.isActive ?? true),
});

/** A new concept has no `isActive` and a code; an edit has `isActive` and no code, because the code never changes. */
export type PaymentConceptSave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

/**
 * The body that only changes the state of a concept, keeping every other field as the row has it. It carries the
 * version the row was read at: if the concept changed since, the server answers 409 `stale-version`.
 */
export const updateBodyOf = (row: PaymentConcept, isActive: boolean): UpdateBody => ({
  version: row.version,
  name: row.name,
  category: row.category,
  isRecurring: row.isRecurring,
  isTaxable: row.isTaxable,
  // The rate the concept holds travels with it: a taxable concept is refused without one, and a concept that is not taxable takes none.
  ...(row.isTaxable && row.taxRateId !== null && row.taxRateId !== undefined ? { taxRateId: row.taxRateId } : {}),
  isActive,
});

/** Whether the concept is taxable and so must name a tax rate (and, when it is not, must not). */
export const needsTaxRate = (isTaxable: string): boolean => isTaxable === "true";

export function saveOf(row: PaymentConcept | undefined, values: PaymentConceptValues): PaymentConceptSave {
  // An empty category is refused by the schema before anything is sent: the cast only lets the schema judge it.
  const category = values.category as PaymentCategory;
  const isRecurring = values.isRecurring === "true";
  const isTaxable = needsTaxRate(values.isTaxable);
  // The rate is sent only for a taxable concept: one that is not taxable takes none, so whatever was chosen before is dropped.
  const taxRate = isTaxable && values.taxRateId !== "" ? { taxRateId: values.taxRateId } : {};
  if (row === undefined) return { mode: "create", body: { code: values.code, name: values.name, category, isRecurring, isTaxable, ...taxRate } };
  return {
    mode: "update",
    id: row.id,
    body: { version: row.version, name: values.name, category, isRecurring, isTaxable, ...taxRate, isActive: values.isActive === "true" },
  };
}

/**
 * The reasons the form is refused before sending: the generated request, and a code, a name and a category that are not
 * blank, and a tax rate when the concept is taxable. A code the generated whitelist pattern refuses (and that is not merely too long) gets its own reason. The form
 * trims the code before it validates and sends it, because the server does not.
 */
export function validatePaymentConcept(row: PaymentConcept | undefined) {
  return (values: PaymentConceptValues): Partial<Record<PaymentConceptField, FieldReason>> => {
    const reasons = reasonsFromSchema(row === undefined ? CreatePaymentConceptRequest : UpdatePaymentConceptRequest, saveOf(row, values).body, FIELDS);
    return {
      ...reasons,
      ...(reasons.code === "format" ? { code: "code-format" as const } : {}),
      ...requiredReasons(values, [...(row === undefined ? (["code", "name", "category"] as const) : (["name", "category"] as const)), ...(needsTaxRate(values.isTaxable) ? (["taxRateId"] as const) : [])]),
    };
  };
}

export function writePaymentConcept(token: string, save: PaymentConceptSave): Promise<ApiResult<PaymentConcept>> {
  return save.mode === "create" ? createPaymentConcept(token, save.body) : updatePaymentConcept(token, save.id, save.body);
}
