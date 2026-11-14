import { z } from "zod";
import { page, pageSize, sortOf } from "../organization/list-search.ts";

/** The server refuses a text shorter than this (`400 validation-failed`), so the screen never sends one. */
export const MIN_QUERY_LENGTH = 2;
const MAX_QUERY_LENGTH = 100;

/**
 * The search parameters of the students list: the common ones (`page`, `pageSize`, `sort`), the text `q` and the
 * `status`. A parameter that does not hold is dropped, so a bad URL never breaks the screen. The sort fields are the
 * ones the API accepts. `q` is trimmed and a text too short to search is dropped here too, like the box does.
 */
export const studentsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["studentCode", "lastName", "firstName", "birthDate"]),
  q: z.string().trim().min(MIN_QUERY_LENGTH).max(MAX_QUERY_LENGTH).optional().catch(undefined),
  status: z.enum(["ACTIVE", "INACTIVE"]).optional().catch(undefined),
});

export type StudentsSearch = z.infer<typeof studentsSearch>;

/** The text a box holds, as the search takes it: trimmed, and absent while it is too short to search. */
export function searchTextOf(draft: string): string | undefined {
  const text = draft.trim();
  return text.length >= MIN_QUERY_LENGTH ? text : undefined;
}
