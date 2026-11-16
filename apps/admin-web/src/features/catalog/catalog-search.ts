import { z } from "zod";
import { optionalId, page, pageSize, sortOf } from "../../shared/resource-screen/list-search.ts";

/** The closed set of categories of a payment concept, in the order a person reads them. */
export const PAYMENT_CATEGORIES = ["TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"] as const;

/**
 * The search parameters of the payment concepts list: the common ones (`page`, `pageSize`, `sort`) and the two filters,
 * `category` and `active`. A parameter that does not hold is dropped, so a bad URL never breaks the screen. The sort
 * fields are the ones the API accepts.
 */
export const paymentConceptsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["code", "name"]),
  category: z.enum(PAYMENT_CATEGORIES).optional().catch(undefined),
  // A boolean: the router reads the address as JSON, so `active=false` arrives as `false`.
  active: z.boolean().optional().catch(undefined),
});

export type PaymentConceptsSearch = z.infer<typeof paymentConceptsSearch>;

/** The scopes of a price list, from the widest to the narrowest. */
export const PRICE_LIST_SCOPES = ["INSTITUTION", "MODALITY", "GRADE"] as const;
export const PRICE_LIST_STATUSES = ["ACTIVE", "ARCHIVED"] as const;

/**
 * The search of the price lists: the common parameters, the three filters the API takes (academic year, scope and
 * status) and the sort fields it accepts. A parameter that does not hold is dropped.
 */
export const priceListsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["name", "createdAt"]),
  academicYearId: optionalId,
  scope: z.enum(PRICE_LIST_SCOPES).optional().catch(undefined),
  status: z.enum(PRICE_LIST_STATUSES).optional().catch(undefined),
});

export type PriceListsSearch = z.infer<typeof priceListsSearch>;
