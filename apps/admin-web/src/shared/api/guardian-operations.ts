import {
  GuardianPageResponse,
  GuardianResponse,
  StudentGuardiansResponse,
  type CreateGuardianRequest,
  type GuardianPageResponseOutput,
  type GuardianResponseOutput,
  type ReplaceStudentGuardiansRequest,
  type StudentGuardiansResponseOutput,
  type UpdateGuardianRequest,
} from "@confia/contracts/admin";
import { request, type ApiResult } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/guardians` and of `/api/v1/students/{id}/guardians`. Each takes the access token as a
 * parameter: this module knows no session. None moves money, so none sends an `Idempotency-Key` (replacing the set of a
 * student is idempotent by nature). Paging, order, the text search and the status filter are the server's and travel as
 * query parameters. The national id and the RTN travel only in the body of a create or an edit, and no response ever
 * carries them: the API answers with `hasNationalId` and `hasRtn`.
 */
const { list, create, update } = resourceClient("/api/v1");

const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });
const studentLinksPath = (studentId: string): string => `/api/v1/students/${encodeURIComponent(studentId)}/guardians`;

export interface GuardianListParams extends PageParams {
  /** Two characters or more: the screen does not send a shorter one, and the server refuses it. */
  readonly q?: string | undefined;
  readonly status?: "ACTIVE" | "INACTIVE" | undefined;
}

export const listGuardians = (token: string, params: GuardianListParams): Promise<ApiResult<GuardianPageResponseOutput>> =>
  list("guardians", GuardianPageResponse, token, params);

export const getGuardian = (token: string, id: string): Promise<ApiResult<GuardianResponseOutput>> =>
  request({ method: "GET", path: `/api/v1/guardians/${encodeURIComponent(id)}`, headers: bearer(token), schema: GuardianResponse });

export const createGuardian = (token: string, body: CreateGuardianRequest): Promise<ApiResult<GuardianResponseOutput>> =>
  create("guardians", GuardianResponse, token, body);

export const updateGuardian = (token: string, id: string, body: UpdateGuardianRequest): Promise<ApiResult<GuardianResponseOutput>> =>
  update("guardians", GuardianResponse, token, id, body);

export const getStudentGuardians = (token: string, studentId: string): Promise<ApiResult<StudentGuardiansResponseOutput>> =>
  request({ method: "GET", path: studentLinksPath(studentId), headers: bearer(token), schema: StudentGuardiansResponse });

export const replaceStudentGuardians = (
  token: string,
  studentId: string,
  body: ReplaceStudentGuardiansRequest,
): Promise<ApiResult<StudentGuardiansResponseOutput>> =>
  request({ method: "PUT", path: studentLinksPath(studentId), headers: bearer(token), body, schema: StudentGuardiansResponse });
