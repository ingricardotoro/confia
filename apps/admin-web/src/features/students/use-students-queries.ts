import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import { listStudents, type StudentListParams } from "../../shared/api/student-operations.ts";
import { writeStudent, type Student, type StudentSave } from "./student-form.ts";
import { studentKeys } from "./students-keys.ts";

/** A listing of students, as the server pages, orders and searches it: the rows come back as they were served. */
export function useStudentList(params: StudentListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: studentKeys.list(params),
    queryFn: () => send((token) => listStudents(token, params)),
  });
}

/**
 * A write of one student. It is not financial, so nothing is optimistic and no `Idempotency-Key` is sent, and it never
 * repeats by itself. After a write the whole registry is invalidated: the lists show the change. The body of a write
 * can hold a national id, so the mutation is let go as soon as it settles (`gcTime: 0` and a reset): nothing in the
 * cache keeps what was sent.
 */
export function useStudentWrite() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    gcTime: 0,
    mutationFn: (save: StudentSave) => send((token) => writeStudent(token, save)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: studentKeys.all }),
  });
  const { mutateAsync, reset } = mutation;

  return async (save: StudentSave): Promise<Student> => {
    try {
      return await mutateAsync(save);
    } finally {
      reset();
    }
  };
}
