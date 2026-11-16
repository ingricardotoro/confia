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
import { activeValueOf } from "../organization/organization-form.ts";

export type PaymentConcept = PaymentConceptResponseOutput;
export type PaymentConceptField = "code" | "name" | "category" | "isRecurring" | "isTaxable" | "isActive";

export const PAYMENT_CONCEPT_CONTROL_IDS: Readonly<Record<PaymentConceptField, string>> = {
  code: "payment-concept-code",
  name: "payment-concept-name",
  category: "payment-concept-category",
  isRecurring: "payment-concept-recurring",
  isTaxable: "payment-concept-taxable",
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
  isActive: activeValueOf(row?.isActive ?? true),
});

/** A new concept has no `isActive` and a code; an edit has `isActive` and no code, because the code never changes. */
export type PaymentConceptSave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

/** The body that only changes the state of a concept, keeping every other field as the row has it. */
export const updateBodyOf = (row: PaymentConcept, isActive: boolean): UpdateBody => ({
  name: row.name,
  category: row.category,
  isRecurring: row.isRecurring,
  isTaxable: row.isTaxable,
  isActive,
});

export function saveOf(row: PaymentConcept | undefined, values: PaymentConceptValues): PaymentConceptSave {
  // An empty category is refused by the schema before anything is sent: the cast only lets the schema judge it.
  const category = values.category as PaymentCategory;
  const isRecurring = values.isRecurring === "true";
  const isTaxable = values.isTaxable === "true";
  if (row === undefined) return { mode: "create", body: { code: values.code, name: values.name, category, isRecurring, isTaxable } };
  return { mode: "update", id: row.id, body: { name: values.name, category, isRecurring, isTaxable, isActive: values.isActive === "true" } };
}

/** The reasons the form is refused before sending: the generated request, and a code, a name and a category that are not blank. */
export function validatePaymentConcept(row: PaymentConcept | undefined) {
  return (values: PaymentConceptValues): Partial<Record<PaymentConceptField, FieldReason>> => ({
    ...reasonsFromSchema(row === undefined ? CreatePaymentConceptRequest : UpdatePaymentConceptRequest, saveOf(row, values).body, FIELDS),
    ...requiredReasons(values, row === undefined ? ["code", "name", "category"] : ["name", "category"]),
  });
}

export function writePaymentConcept(token: string, save: PaymentConceptSave): Promise<ApiResult<PaymentConcept>> {
  return save.mode === "create" ? createPaymentConcept(token, save.body) : updatePaymentConcept(token, save.id, save.body);
}
