import { useQuery } from "@tanstack/react-query";
import { listStudents } from "../api/student-operations.ts";
import { useAuthorizedSend } from "../api/authorized-send.tsx";
import { LOOKUP_PAGE_SIZE } from "../organization-lookups/use-organization-queries.ts";
import type { Lookup } from "../organization-lookups/use-lookups.ts";
import { studentKeys } from "../query-keys/students-keys.ts";

/** What a screen of another area needs of a student: to name them. Never the national id, which no response carries. */
export interface StudentLookupRow {
  readonly id: string;
  readonly studentCode: string;
  readonly firstName: string;
  readonly lastName: string;
}

/**
 * The students a screen names: one page of the largest size the API gives, in the order of the surname. It shares its key
 * with the registry's list, so a write there refreshes it. A lookup is a label, never a filter of a table: a student that
 * is not on the page shows as not available.
 */
export function useStudentLookup(): Lookup<StudentLookupRow> {
  const send = useAuthorizedSend();
  const params = { page: 0, size: LOOKUP_PAGE_SIZE, sort: "lastName,asc" };
  const list = useQuery({
    queryKey: studentKeys.list(params),
    queryFn: () => send((token) => listStudents(token, params)),
  });
  return { rows: list.data?.items ?? [], failed: list.isError, loading: list.isPending, retry: () => void list.refetch() };
}
