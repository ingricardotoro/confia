/**
 * Query keys of the scholarships, general to specific, so a prefix invalidates what it covers: everything about scholarships, every list, one
 * list, every detail, one detail. Nothing writes a literal key inline.
 */
export const scholarshipKeys = {
  all: ["scholarships", "scholarship"] as const,
  lists: () => [...scholarshipKeys.all, "list"] as const,
  list: (params: object) => [...scholarshipKeys.lists(), params] as const,
};

/** Query keys of the awards: everything about awards, every list, one list, every detail, one detail. */
export const awardKeys = {
  all: ["scholarships", "award"] as const,
  lists: () => [...awardKeys.all, "list"] as const,
  list: (params: object) => [...awardKeys.lists(), params] as const,
  details: () => [...awardKeys.all, "detail"] as const,
  detail: (id: string) => [...awardKeys.details(), id] as const,
};

/**
 * The lookups of the simulator of charges: the active plans and the periods of one plan. The simulation itself is a calculation the server does and
 * writes nothing, so it is not cached and has no key.
 */
export const simulationKeys = {
  all: ["scholarships", "simulation"] as const,
  plans: (params: object) => [...simulationKeys.all, "plans", params] as const,
  periods: (planId: string) => [...simulationKeys.all, "periods", planId] as const,
};

/** Query keys of the discounts: everything about discounts, every list, one list. */
export const discountKeys = {
  all: ["scholarships", "discount"] as const,
  lists: () => [...discountKeys.all, "list"] as const,
  list: (params: object) => [...discountKeys.lists(), params] as const,
};

/** Query keys of the agreement assignments: everything about them, every list, one list, every detail, one detail. */
export const assignmentKeys = {
  all: ["scholarships", "discount-assignment"] as const,
  lists: () => [...assignmentKeys.all, "list"] as const,
  list: (params: object) => [...assignmentKeys.lists(), params] as const,
  details: () => [...assignmentKeys.all, "detail"] as const,
  detail: (id: string) => [...assignmentKeys.details(), id] as const,
};
