/**
 * Query keys of the guardians, general to specific, so a prefix invalidates what it covers: all of them, every list,
 * every detail, every set of links of a student, one of each. Nothing writes a literal key inline. A key holds the search
 * the server was asked and ids; it never holds a name, a document or an RTN.
 */
export const guardianKeys = {
  all: ["guardians"] as const,
  lists: () => [...guardianKeys.all, "list"] as const,
  list: (params: object) => [...guardianKeys.lists(), params] as const,
  details: () => [...guardianKeys.all, "detail"] as const,
  detail: (id: string) => [...guardianKeys.details(), id] as const,
  studentLinksAll: () => [...guardianKeys.all, "student-links"] as const,
  studentLinks: (studentId: string) => [...guardianKeys.studentLinksAll(), studentId] as const,
};
