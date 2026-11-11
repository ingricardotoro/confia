/**
 * Query keys of the students registry, general to specific, so a prefix invalidates what it covers: the whole
 * registry, every list, one list. Nothing writes a literal key inline. The key holds the search the server was asked
 * (page, size, order, text, status); it never holds a national id, which no query sends.
 */
export const studentKeys = {
  all: ["students"] as const,
  lists: () => [...studentKeys.all, "list"] as const,
  list: (params: object) => [...studentKeys.lists(), params] as const,
};
