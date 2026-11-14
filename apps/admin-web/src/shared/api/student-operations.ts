import {
  StudentPageResponse,
  StudentResponse,
  type CreateStudentRequest,
  type StudentPageResponseOutput,
  type StudentResponseOutput,
  type UpdateStudentRequest,
} from "@confia/contracts/admin";
import type { ApiResult } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/students`. Each takes the access token as a parameter: this module knows no session.
 * None moves money, so none sends an `Idempotency-Key`. Paging, order, the text search and the status filter are
 * the server's and travel as query parameters. The national id travels only in the body of a create or an edit,
 * and no response ever carries it: the API answers with the last four characters, already masked.
 */
const { list, create, update } = resourceClient("/api/v1");

export interface StudentListParams extends PageParams {
  /** Two characters or more: the screen does not send a shorter one, and the server refuses it. */
  readonly q?: string | undefined;
  readonly status?: "ACTIVE" | "INACTIVE" | undefined;
}

export const listStudents = (token: string, params: StudentListParams): Promise<ApiResult<StudentPageResponseOutput>> =>
  list("students", StudentPageResponse, token, params);
export const createStudent = (token: string, body: CreateStudentRequest): Promise<ApiResult<StudentResponseOutput>> =>
  create("students", StudentResponse, token, body);
export const updateStudent = (token: string, id: string, body: UpdateStudentRequest): Promise<ApiResult<StudentResponseOutput>> =>
  update("students", StudentResponse, token, id, body);
