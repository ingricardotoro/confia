import {
  CreateTaxRateRequest,
  UpdateTaxRateRequest,
  type CreateTaxRateRequest as CreateBody,
  type TaxRateDetailResponseOutput,
  type TaxRatePeriodResponseOutput,
  type TaxRateResponseOutput,
  type UpdateTaxRateRequest as UpdateBody,
} from "@confia/contracts/admin";
import { createTaxRate, updateTaxRate } from "../../shared/api/catalog-operations.ts";
import type { ApiResult } from "../../shared/api/http.ts";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";

export type TaxRate = TaxRateResponseOutput;
export type TaxRateDetail = TaxRateDetailResponseOutput;
export type TaxRatePeriod = TaxRatePeriodResponseOutput;
export type TaxRateField = "code" | "name";

export const TAX_RATE_CONTROL_IDS: Readonly<Record<TaxRateField, string>> = {
  code: "tax-rate-code",
  name: "tax-rate-name",
};

const FIELDS = Object.keys(TAX_RATE_CONTROL_IDS) as TaxRateField[];

export type TaxRateValues = Readonly<Record<TaxRateField, string>>;

export const initialTaxRateValues = (row?: TaxRate): TaxRateValues => ({ code: row?.code ?? "", name: row?.name ?? "" });

/** A new rate has a code and a name; an edit has the name only, because the code never changes and the rate keeps its state. */
export type TaxRateSave =
  | { readonly mode: "create"; readonly body: CreateBody }
  | { readonly mode: "update"; readonly id: string; readonly body: UpdateBody };

/**
 * The body that changes the state of a rate (and only that), with the version the rate was read at: if it changed
 * since, the server answers 409 `stale-version`.
 */
export const stateBodyOf = (rate: { readonly version: number; readonly name: string }, isActive: boolean): UpdateBody => ({
  version: rate.version,
  name: rate.name,
  isActive,
});

export function saveOf(row: TaxRate | undefined, values: TaxRateValues): TaxRateSave {
  if (row === undefined) return { mode: "create", body: { code: values.code, name: values.name } };
  return { mode: "update", id: row.id, body: { version: row.version, name: values.name, isActive: row.isActive } };
}

/**
 * The reasons the form is refused before sending: the generated request, and a code and a name that are not blank. A
 * code the generated whitelist pattern refuses (and that is not merely too long) gets its own reason.
 */
export function validateTaxRate(row: TaxRate | undefined) {
  return (values: TaxRateValues): Partial<Record<TaxRateField, FieldReason>> => {
    const reasons = reasonsFromSchema(row === undefined ? CreateTaxRateRequest : UpdateTaxRateRequest, saveOf(row, values).body, FIELDS);
    return {
      ...reasons,
      ...(reasons.code === "format" ? { code: "code-format" as const } : {}),
      ...requiredReasons(values, row === undefined ? ["code", "name"] : ["name"]),
    };
  };
}

export function writeTaxRate(token: string, save: TaxRateSave): Promise<ApiResult<TaxRate>> {
  return save.mode === "create" ? createTaxRate(token, save.body) : updateTaxRate(token, save.id, save.body);
}
