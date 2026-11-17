/**
 * Query keys of the charges plans, general to specific, so a prefix invalidates what it covers: every plan, every
 * listing, one listing, every detail, one detail. Nothing writes a literal key inline.
 */
export const chargePlanKeys = {
  all: ["charges", "plans"] as const,
  lists: () => [...chargePlanKeys.all, "list"] as const,
  list: (params: object) => [...chargePlanKeys.lists(), params] as const,
  details: () => [...chargePlanKeys.all, "detail"] as const,
  detail: (id: string) => [...chargePlanKeys.details(), id] as const,
};
