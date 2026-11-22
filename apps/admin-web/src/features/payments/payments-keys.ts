/**
 * Query keys of the payments, general to specific, so a prefix invalidates what it covers: everything the payments show, every list,
 * one list, every detail, one detail. A write that moves money (register, confirm, reject) invalidates the `all` prefix, and with it
 * the statements, the charges of the student and the cashbox, whose keys live in `shared/query-keys`. Nothing writes a literal key inline.
 */
export const paymentKeys = {
  all: ["payments"] as const,
  lists: () => [...paymentKeys.all, "list"] as const,
  list: (params: object) => [...paymentKeys.lists(), params] as const,
  details: () => [...paymentKeys.all, "detail"] as const,
  detail: (id: string) => [...paymentKeys.details(), id] as const,
};
