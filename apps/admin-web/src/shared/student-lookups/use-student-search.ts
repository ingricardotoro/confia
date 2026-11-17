import { useQuery } from "@tanstack/react-query";
import { useAuthorizedSend } from "../api/authorized-send.tsx";
import { listStudents } from "../api/student-operations.ts";
import { studentKeys } from "../query-keys/students-keys.ts";

/** The most students a search offers: a person who needs one further down types more of the name. */
export const STUDENT_SEARCH_RESULTS = 10;

/**
 * The search of a student by name, last name or code, for a screen that has to point at one. The server does the searching
 * (the students list with its `q` parameter, every status), so a registry of any size is searchable. Nothing is asked
 * without a text of two characters or more, which is what `text` being absent means.
 */
export function useStudentSearch(text: string | undefined) {
  const send = useAuthorizedSend();
  const params = { page: 0, size: STUDENT_SEARCH_RESULTS, sort: "lastName,asc", ...(text === undefined ? {} : { q: text }) };
  return useQuery({
    queryKey: studentKeys.list(params),
    queryFn: () => send((token) => listStudents(token, params)),
    enabled: text !== undefined,
  });
}
