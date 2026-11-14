import type { ReplaceStudentGuardiansRequest, StudentGuardiansResponseOutput } from "@confia/contracts/admin";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import {
  getGuardian,
  getStudentGuardians,
  listGuardians,
  replaceStudentGuardians,
  type GuardianListParams,
} from "../../shared/api/guardian-operations.ts";
import { writeGuardian, type Guardian, type GuardianSave } from "./guardian-form.ts";
import { guardianKeys } from "./guardians-keys.ts";

/** A listing of guardians, as the server pages, orders and searches it: the rows come back as they were served. */
export function useGuardianList(params: GuardianListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: guardianKeys.list(params),
    queryFn: () => send((token) => listGuardians(token, params)),
  });
}

/** One guardian with the students it is linked to now; it is read only while a detail is open. */
export function useGuardianDetail(id: string) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: guardianKeys.detail(id),
    queryFn: () => send((token) => getGuardian(token, id)),
  });
}

/** The most guardians the search offers: a person who needs one further down types more of the name. */
const GUARDIAN_RESULTS = 10;

/**
 * The search of a guardian to add to a student: the existing guardians list, asked for active guardians only (the server
 * refuses a link to another), by text. Nothing is asked without a text of two characters or more.
 */
export function useGuardianSearch(text: string | undefined) {
  const send = useAuthorizedSend();
  const params = { page: 0, size: GUARDIAN_RESULTS, sort: "lastName,asc", status: "ACTIVE" as const, ...(text === undefined ? {} : { q: text }) };
  return useQuery({
    queryKey: guardianKeys.list(params),
    queryFn: () => send((token) => listGuardians(token, params)),
    enabled: text !== undefined,
  });
}

/** The current set of guardians of a student, with the sum the server calculated. */
export function useStudentGuardians(studentId: string) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: guardianKeys.studentLinks(studentId),
    queryFn: () => send((token) => getStudentGuardians(token, studentId)),
  });
}

/**
 * A write of one guardian. It is not financial, so nothing is optimistic and no `Idempotency-Key` is sent, and it never
 * repeats by itself. After a write everything of the guardians is invalidated (lists, details, sets of links). The body can
 * hold a national id or an RTN, so the mutation is let go as soon as it settles (`gcTime: 0` and a reset): nothing in the
 * cache keeps what was sent.
 */
export function useGuardianWrite() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    gcTime: 0,
    mutationFn: (save: GuardianSave) => send((token) => writeGuardian(token, save)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: guardianKeys.all }),
  });
  const { mutateAsync, reset } = mutation;

  return async (save: GuardianSave): Promise<Guardian> => {
    try {
      return await mutateAsync(save);
    } finally {
      reset();
    }
  };
}

/**
 * The replacement of the whole set of guardians of a student. Not financial and idempotent by nature, so no
 * `Idempotency-Key`; nothing is optimistic (the answer of the server, with its sum, is what the screen shows); it never
 * repeats by itself, and the dialog blocks a second send while one is in flight. The guardians' details list their students
 * too, so everything of the guardians is invalidated.
 */
export function useReplaceStudentGuardians(studentId: string) {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    gcTime: 0,
    mutationFn: (body: ReplaceStudentGuardiansRequest) => send((token) => replaceStudentGuardians(token, studentId, body)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: guardianKeys.all }),
  });
  const { mutateAsync, reset } = mutation;

  return async (body: ReplaceStudentGuardiansRequest): Promise<StudentGuardiansResponseOutput> => {
    try {
      return await mutateAsync(body);
    } finally {
      reset();
    }
  };
}
