/**
 * Query keys of the payment concepts, general to specific, so a prefix invalidates what it covers: every list, one list.
 * They live in `shared` because two areas read the concepts (the catalog, which edits them, and the charges, which name
 * them in a plan), and a feature never imports another. Nothing writes a literal key inline.
 */
export const paymentConceptKeys = {
  all: ["catalog", "payment-concepts"] as const,
  lists: () => [...paymentConceptKeys.all, "list"] as const,
  list: (params: object) => [...paymentConceptKeys.lists(), params] as const,
};
