/**
 * Query keys of the cashbox, general to specific, so a prefix invalidates what it covers: everything the cashbox shows, the session of
 * the person, every list, one list, every detail, one detail. A write that moves cash (open, move, close, approve) invalidates the
 * `all` prefix. Nothing writes a literal key inline.
 */
export const cashboxKeys = {
  all: ["cashbox"] as const,
  current: () => [...cashboxKeys.all, "current"] as const,
  lists: () => [...cashboxKeys.all, "list"] as const,
  list: (params: object) => [...cashboxKeys.lists(), params] as const,
  details: () => [...cashboxKeys.all, "detail"] as const,
  detail: (id: string) => [...cashboxKeys.details(), id] as const,
};
