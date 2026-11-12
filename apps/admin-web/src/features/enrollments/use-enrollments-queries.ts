import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { EnrollStudentRequest, ExitEnrollmentRequest, TransferEnrollmentRequest } from "@confia/contracts/admin";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import {
  enrollStudent,
  exitEnrollment,
  getEnrollment,
  listEnrollments,
  transferEnrollment,
  type EnrollmentListParams,
} from "../../shared/api/enrollment-operations.ts";
import { listSections } from "../../shared/api/organization-operations.ts";
import { listStudents } from "../../shared/api/student-operations.ts";
import { organizationKeys } from "../organization/organization-keys.ts";
import { LOOKUP_PAGE_SIZE } from "../organization/use-organization-queries.ts";
import { studentKeys } from "../students/students-keys.ts";
import type { Enrollment } from "./enrollment-model.ts";
import { enrollmentKeys } from "./enrollments-keys.ts";

/** A listing of enrollments, as the server pages, orders and filters it: the rows come back as they were served. */
export function useEnrollmentList(params: EnrollmentListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: enrollmentKeys.list(params),
    queryFn: () => send((token) => listEnrollments(token, params)),
  });
}

/** One enrollment with its history of section transfers; it is read only while a detail is open. */
export function useEnrollmentDetail(id: string) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: enrollmentKeys.detail(id),
    queryFn: () => send((token) => getEnrollment(token, id)),
  });
}

/**
 * The sections of a year, as a lookup of the largest page the API gives: to filter the list and to fill the selects of
 * the dialogs. With `enabled` false (the year is not known yet) nothing is asked. Written under the keys of the
 * academic structure, so a change there refreshes it.
 */
export function useYearSections(academicYearId: string | undefined, enabled = true) {
  const send = useAuthorizedSend();
  const params = { page: 0, size: LOOKUP_PAGE_SIZE, sort: "name,asc", ...(academicYearId === undefined ? {} : { academicYearId }) };
  return useQuery({
    queryKey: organizationKeys.list("sections", params),
    queryFn: () => send((token) => listSections(token, params)),
    enabled,
  });
}

/** The most students the search offers: a person who needs one further down types more of the name. */
const STUDENT_RESULTS = 10;

/**
 * The search of a student to enroll: the existing students list, asked for active students only (the server refuses to
 * enroll another), by text. Nothing is asked without a text of two characters or more.
 */
export function useStudentSearch(text: string | undefined) {
  const send = useAuthorizedSend();
  const params = { page: 0, size: STUDENT_RESULTS, sort: "lastName,asc", status: "ACTIVE" as const, ...(text === undefined ? {} : { q: text }) };
  return useQuery({
    queryKey: studentKeys.list(params),
    queryFn: () => send((token) => listStudents(token, params)),
    enabled: text !== undefined,
  });
}

export type EnrollmentWrite =
  | { readonly kind: "enroll"; readonly body: EnrollStudentRequest }
  | { readonly kind: "transfer"; readonly id: string; readonly body: TransferEnrollmentRequest }
  | { readonly kind: "exit"; readonly id: string; readonly body: ExitEnrollmentRequest };

function writeEnrollment(token: string, write: EnrollmentWrite) {
  switch (write.kind) {
    case "enroll":
      return enrollStudent(token, write.body);
    case "transfer":
      return transferEnrollment(token, write.id, write.body);
    case "exit":
      return exitEnrollment(token, write.id, write.body);
  }
}

/**
 * A write of one enrollment. It is not financial, so nothing is optimistic and no `Idempotency-Key` is sent, and it
 * never repeats by itself: a second try of a transition that already happened answers `409 invalid-enrollment-state`,
 * and the dialog blocks a second send while one is in flight. After a write every list and every detail is invalidated.
 * The body can hold a reason in the person's words, so the mutation is let go as soon as it settles (`gcTime: 0` and a
 * reset): nothing in the cache keeps what was sent.
 */
export function useEnrollmentWrite() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    gcTime: 0,
    mutationFn: (write: EnrollmentWrite) => send((token) => writeEnrollment(token, write)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: enrollmentKeys.all }),
  });
  const { mutateAsync, reset } = mutation;

  return async (write: EnrollmentWrite): Promise<Enrollment> => {
    try {
      return await mutateAsync(write);
    } finally {
      reset();
    }
  };
}
