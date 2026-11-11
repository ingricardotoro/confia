import {
  AcademicYearPageResponse,
  AcademicYearResponse,
  GradePageResponse,
  GradeResponse,
  ModalityPageResponse,
  ModalityResponse,
  SectionPageResponse,
  SectionResponse,
  type AcademicYearPageResponseOutput,
  type AcademicYearResponseOutput,
  type CreateAcademicYearRequest,
  type CreateGradeRequest,
  type CreateModalityRequest,
  type CreateSectionRequest,
  type GradePageResponseOutput,
  type GradeResponseOutput,
  type ModalityPageResponseOutput,
  type ModalityResponseOutput,
  type SectionPageResponseOutput,
  type SectionResponseOutput,
  type UpdateAcademicYearRequest,
  type UpdateGradeRequest,
  type UpdateModalityRequest,
  type UpdateSectionRequest,
} from "@confia/contracts/admin";
import type { ApiResult } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/organization` (the academic structure). Each takes the access token as a
 * parameter, like the operations of access: this module knows no session. None moves money, so none sends
 * an `Idempotency-Key`. Paging, order and filters are the server's: they travel as query parameters and
 * nothing here, or after it, sorts or filters a page in the browser.
 */
const { list, create, update } = resourceClient("/api/v1/organization");

export type { PageParams };

export interface GradeListParams extends PageParams {
  readonly modalityId?: string | undefined;
}

export interface SectionListParams extends PageParams {
  readonly academicYearId?: string | undefined;
  readonly gradeId?: string | undefined;
}

export const listAcademicYears = (token: string, params: PageParams): Promise<ApiResult<AcademicYearPageResponseOutput>> =>
  list("academic-years", AcademicYearPageResponse, token, params);
export const createAcademicYear = (token: string, body: CreateAcademicYearRequest): Promise<ApiResult<AcademicYearResponseOutput>> =>
  create("academic-years", AcademicYearResponse, token, body);
export const updateAcademicYear = (
  token: string,
  id: string,
  body: UpdateAcademicYearRequest,
): Promise<ApiResult<AcademicYearResponseOutput>> => update("academic-years", AcademicYearResponse, token, id, body);

export const listModalities = (token: string, params: PageParams): Promise<ApiResult<ModalityPageResponseOutput>> =>
  list("modalities", ModalityPageResponse, token, params);
export const createModality = (token: string, body: CreateModalityRequest): Promise<ApiResult<ModalityResponseOutput>> =>
  create("modalities", ModalityResponse, token, body);
export const updateModality = (token: string, id: string, body: UpdateModalityRequest): Promise<ApiResult<ModalityResponseOutput>> =>
  update("modalities", ModalityResponse, token, id, body);

export const listGrades = (token: string, params: GradeListParams): Promise<ApiResult<GradePageResponseOutput>> =>
  list("grades", GradePageResponse, token, params);
export const createGrade = (token: string, body: CreateGradeRequest): Promise<ApiResult<GradeResponseOutput>> =>
  create("grades", GradeResponse, token, body);
export const updateGrade = (token: string, id: string, body: UpdateGradeRequest): Promise<ApiResult<GradeResponseOutput>> =>
  update("grades", GradeResponse, token, id, body);

export const listSections = (token: string, params: SectionListParams): Promise<ApiResult<SectionPageResponseOutput>> =>
  list("sections", SectionPageResponse, token, params);
export const createSection = (token: string, body: CreateSectionRequest): Promise<ApiResult<SectionResponseOutput>> =>
  create("sections", SectionResponse, token, body);
export const updateSection = (token: string, id: string, body: UpdateSectionRequest): Promise<ApiResult<SectionResponseOutput>> =>
  update("sections", SectionResponse, token, id, body);
