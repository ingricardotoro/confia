import { useQuery } from "@tanstack/react-query";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import { getStudentStatement, type StatementParams } from "../../shared/api/charges-operations.ts";
import { statementKeys } from "./charges-keys.ts";

/**
 * The statement of one student, as the server derives it from the ledger: balances and the running balance of every line
 * are the server's. It is a read, never optimistic and never kept apart from the cache: a write that books charges
 * invalidates `statementKeys.all`. Nothing is read until a student is chosen.
 */
export function useStudentStatement(studentId: string | undefined, params: StatementParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: statementKeys.detail(studentId ?? "", params),
    queryFn: () => send((token) => getStudentStatement(token, studentId ?? "", params)),
    enabled: studentId !== undefined,
    // Changing the page keeps the page on screen until the next arrives; changing the student never shows the previous student.
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[2] === studentId ? previous : undefined),
  });
}
