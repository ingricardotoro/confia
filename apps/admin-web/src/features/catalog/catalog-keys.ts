/**
 * Query keys of the catalog, general to specific, so a prefix invalidates what it covers: the whole catalog, every list,
 * one list. Nothing writes a literal key inline.
 */
export const paymentConceptKeys = {
  all: ["catalog", "payment-concepts"] as const,
  lists: () => [...paymentConceptKeys.all, "list"] as const,
  list: (params: object) => [...paymentConceptKeys.lists(), params] as const,
};
