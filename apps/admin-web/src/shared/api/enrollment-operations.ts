import {
  EnrollmentPageResponse,
  StudentEnrollmentResponse,
  type EnrollStudentRequest,
  type EnrollmentPageResponseOutput,
  type ExitEnrollmentRequest,
  type StudentEnrollmentResponseOutput,
  type TransferEnrollmentRequest,
} from "@confia/contracts/admin";
import { request, type ApiResult } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/enrollments`. Each takes the access token as a parameter: this module knows no session.
 * None moves money, so none sends an `Idempotency-Key`: repeating a transition that is already done answers
 * `409 invalid-enrollment-state`. Paging, order and filters are the server's and travel as query parameters. The year of
 * an enrollment is the section's, so the browser never sends one; the list takes one only as a filter.
 */
const { list, create } = resourceClient("/api/v1");

const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });
const pathOf = (id: string, action?: string): string => `/api/v1/enrollments/${encodeURIComponent(id)}${action === undefined ? "" : `/${action}`}`;

export type EnrollmentStatus = StudentEnrollmentResponseOutput["status"];

export interface EnrollmentListParams extends PageParams {
  /** Absent: the server uses the year in progress, or every year when none is. */
  readonly academicYearId?: string | undefined;
  readonly sectionId?: string | undefined;
  readonly gradeId?: string | undefined;
  readonly status?: EnrollmentStatus | undefined;
}

export const listEnrollments = (token: string, params: EnrollmentListParams): Promise<ApiResult<EnrollmentPageResponseOutput>> =>
  list("enrollments", EnrollmentPageResponse, token, params);

export const getEnrollment = (token: string, id: string): Promise<ApiResult<StudentEnrollmentResponseOutput>> =>
  request({ method: "GET", path: pathOf(id), headers: bearer(token), schema: StudentEnrollmentResponse });

export const enrollStudent = (token: string, body: EnrollStudentRequest): Promise<ApiResult<StudentEnrollmentResponseOutput>> =>
  create("enrollments", StudentEnrollmentResponse, token, body);

export const transferEnrollment = (
  token: string,
  id: string,
  body: TransferEnrollmentRequest,
): Promise<ApiResult<StudentEnrollmentResponseOutput>> =>
  request({ method: "POST", path: pathOf(id, "transfer"), headers: bearer(token), body, schema: StudentEnrollmentResponse });

export const exitEnrollment = (token: string, id: string, body: ExitEnrollmentRequest): Promise<ApiResult<StudentEnrollmentResponseOutput>> =>
  request({ method: "POST", path: pathOf(id, "exit"), headers: bearer(token), body, schema: StudentEnrollmentResponse });
