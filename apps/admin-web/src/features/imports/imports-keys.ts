/**
 * Query keys of the imports, general to specific, so a prefix invalidates what it covers. The history of batches and the
 * actas of the reconciliation are queries (the preview of a year is one too, under the actas): the answer of a simulation
 * or an apply is kept by the screen for as long as it is open and holds counts and the line, field and reason of each
 * rejection, never a value of the file. Nothing writes a literal key inline.
 */
export const importKeys = {
  all: ["imports"] as const,
  lists: () => [...importKeys.all, "list"] as const,
  list: (params: object) => [...importKeys.lists(), params] as const,
  reconciliations: () => [...importKeys.all, "reconciliations"] as const,
  reconciliationLists: () => [...importKeys.reconciliations(), "list"] as const,
  reconciliationList: (params: object) => [...importKeys.reconciliationLists(), params] as const,
  reconciliationPreviews: () => [...importKeys.reconciliations(), "preview"] as const,
  reconciliationPreview: (academicYearId: string) => [...importKeys.reconciliationPreviews(), academicYearId] as const,
};
