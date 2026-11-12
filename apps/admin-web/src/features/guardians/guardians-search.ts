import { z } from "zod";
import { page, pageSize, sortOf } from "../organization/list-search.ts";
import { MIN_QUERY_LENGTH } from "../students/students-search.ts";

const MAX_QUERY_LENGTH = 100;

/**
 * The search parameters of the guardians list: the common ones (`page`, `pageSize`, `sort`), the text `q` and the
 * `status`. A parameter that does not hold is dropped, so a bad URL never breaks the screen. The sort fields are the
 * ones the API accepts, and they are also the ids of the sortable columns. `q` is trimmed and a text too short to search
 * is dropped, as the box does. It is a search term the person typed: it may be a name, so it is the only personal text
 * that reaches the address, and never a document or an RTN (the box has no way to send those).
 */
export const guardiansSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["lastName", "code", "status"]),
  q: z.string().trim().min(MIN_QUERY_LENGTH).max(MAX_QUERY_LENGTH).optional().catch(undefined),
  status: z.enum(["ACTIVE", "INACTIVE"]).optional().catch(undefined),
});

export type GuardiansSearch = z.infer<typeof guardiansSearch>;
