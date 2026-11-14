/**
 * Query keys of the enrollments, general to specific, so a prefix invalidates what it covers: all of them, every
 * list, every detail, one of each. Nothing writes a literal key inline. A key holds the search the server was asked
 * and ids; it never holds a name or a reason.
 */
export const enrollmentKeys = {
  all: ["enrollments"] as const,
  lists: () => [...enrollmentKeys.all, "list"] as const,
  list: (params: object) => [...enrollmentKeys.lists(), params] as const,
  details: () => [...enrollmentKeys.all, "detail"] as const,
  detail: (id: string) => [...enrollmentKeys.details(), id] as const,
};
