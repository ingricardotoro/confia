import type { ImportRunResponseOutput } from "@confia/contracts/admin";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import { applyImport, listImportBatches, simulateImport } from "../../shared/api/import-operations.ts";
import type { PageParams } from "../../shared/api/resource-client.ts";
import { enrollmentKeys } from "../enrollments/enrollments-keys.ts";
import { guardianKeys } from "../guardians/guardians-keys.ts";
import { studentKeys } from "../students/students-keys.ts";
import type { ImportKind } from "./import-model.ts";
import { importKeys } from "./imports-keys.ts";

/** The history of batches, as the server pages it: the rows come back as they were served. */
export function useImportHistory(params: PageParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: importKeys.list(params),
    queryFn: () => send((token) => listImportBatches(token, params)),
  });
}

type Send = (file: File) => Promise<ImportRunResponseOutput>;

/**
 * The shape both writes share. The file travels in the call and is not kept by the mutation: `gcTime: 0` and a reset the
 * moment it settles, so neither the file nor the answer stays in the cache (the answer goes to the screen, which keeps
 * the counts and the lines of the rejections and nothing else). Nothing is optimistic and nothing repeats by itself: a
 * second send waits for the screen, which blocks it while one is in flight.
 */
function useImportRun(operation: (token: string, file: File) => ReturnType<typeof simulateImport>, invalidates: readonly (readonly unknown[])[]): Send {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    gcTime: 0,
    retry: false,
    mutationFn: (file: File) => send((token) => operation(token, file)),
    onSuccess: () => Promise.all(invalidates.map((queryKey) => queryClient.invalidateQueries({ queryKey }))),
  });
  const { mutateAsync, reset } = mutation;

  return async (file) => {
    try {
      return await mutateAsync(file);
    } finally {
      reset();
    }
  };
}

/** Validates a file of this kind and counts what an apply would do. It writes nothing, only its own batch in the history. */
export function useSimulateImport(kind: ImportKind): Send {
  return useImportRun((token, file) => simulateImport(kind, token, file), [importKeys.all]);
}

/** What the registries show again once the rows of a kind are created: the students, the guardians, both for the links, or the enrollments and the students (their enrollment) for the enrollments. */
const AFFECTED: Readonly<Record<ImportKind, readonly (readonly unknown[])[]>> = {
  students: [studentKeys.all],
  guardians: [guardianKeys.all],
  "guardian-links": [guardianKeys.all, studentKeys.all],
  enrollments: [enrollmentKeys.all, studentKeys.all],
};

/** Creates what the valid rows describe. The registries it fills are read again afterwards, and so is the history. */
export function useApplyImport(kind: ImportKind): Send {
  return useImportRun((token, file) => applyImport(kind, token, file), [importKeys.all, ...AFFECTED[kind]]);
}
