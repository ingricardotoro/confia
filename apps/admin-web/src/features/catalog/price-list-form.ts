import {
  CreatePriceListRequest,
  type CreatePriceListRequest as CreateBody,
  type PriceListDetailResponseOutput,
  type PriceListResponseOutput,
  type UpdatePriceListRequest as UpdateBody,
} from "@confia/contracts/admin";
import type { PriceListScope, PriceListStatus } from "../../shared/api/catalog-operations.ts";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";

export type PriceList = PriceListResponseOutput;
export type PriceListDetail = PriceListDetailResponseOutput;
export type PriceListField = "academicYearId" | "scope" | "scopeRefId" | "name";

export const PRICE_LIST_CONTROL_IDS: Readonly<Record<PriceListField, string>> = {
  academicYearId: "price-list-year",
  scope: "price-list-scope",
  scopeRefId: "price-list-scope-ref",
  name: "price-list-name",
};

const FIELDS = Object.keys(PRICE_LIST_CONTROL_IDS) as PriceListField[];

export type PriceListValues = Readonly<Record<PriceListField, string>>;

/** A new list starts for the whole institution, the widest scope; the year is the one thing the person must choose. */
export const initialPriceListValues = (): PriceListValues => ({ academicYearId: "", scope: "INSTITUTION", scopeRefId: "", name: "" });

/** Whether the scope points at a modality or a grade, so the form asks for it. */
export const needsReference = (scope: string): boolean => scope === "MODALITY" || scope === "GRADE";

/** The request of a new list. The reference is sent only for the two scopes that have one. */
export function createBodyOf(values: PriceListValues): CreateBody {
  // An empty scope is refused by the schema before anything is sent: the cast only lets the schema judge it.
  const scope = values.scope as PriceListScope;
  return {
    academicYearId: values.academicYearId,
    name: values.name,
    scope,
    ...(needsReference(values.scope) ? { scopeRefId: values.scopeRefId } : {}),
  };
}

/**
 * The reasons the form is refused before sending: the generated request, and a year, a name, a scope and, for a
 * modality or a grade, the reference that are not blank.
 */
export function validatePriceList(values: PriceListValues): Partial<Record<PriceListField, FieldReason>> {
  return {
    ...reasonsFromSchema(CreatePriceListRequest, createBodyOf(values), FIELDS),
    ...requiredReasons(values, needsReference(values.scope) ? ["academicYearId", "scope", "scopeRefId", "name"] : ["academicYearId", "scope", "name"]),
  };
}

/**
 * The body that changes only the status of a list, with the version the list was read at: if it changed since, the
 * server answers 409 `stale-version`.
 */
export const statusBodyOf = (list: PriceList, status: PriceListStatus): UpdateBody => ({ version: list.version, name: list.name, status });
