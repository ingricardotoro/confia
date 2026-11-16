/**
 * Query keys of the catalog, general to specific, so a prefix invalidates what it covers: the whole catalog, every
 * list, one list. Nothing writes a literal key inline.
 */
export const paymentConceptKeys = {
  all: ["catalog", "payment-concepts"] as const,
  lists: () => [...paymentConceptKeys.all, "list"] as const,
  list: (params: object) => [...paymentConceptKeys.lists(), params] as const,
};

/** The price lists: every listing, and every detail (a list with all the validities of its prices). */
export const priceListKeys = {
  all: ["catalog", "price-lists"] as const,
  lists: () => [...priceListKeys.all, "list"] as const,
  list: (params: object) => [...priceListKeys.lists(), params] as const,
  details: () => [...priceListKeys.all, "detail"] as const,
  detail: (id: string) => [...priceListKeys.details(), id] as const,
};
