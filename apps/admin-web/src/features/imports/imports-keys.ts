/**
 * Query keys of the imports, general to specific, so a prefix invalidates what it covers. Only the history of batches is a
 * query: the answer of a simulation or an apply is kept by the screen for as long as it is open and holds counts and the
 * line, field and reason of each rejection, never a value of the file. Nothing writes a literal key inline.
 */
export const importKeys = {
  all: ["imports"] as const,
  lists: () => [...importKeys.all, "list"] as const,
  list: (params: object) => [...importKeys.lists(), params] as const,
};
