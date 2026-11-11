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
import { request, type ApiResult, type ResponseSchema } from "./http.ts";

/**
 * The operations of `/api/v1/organization` (the academic structure). Each takes the access token as a
 * parameter, like the operations of access: this module knows no session. None moves money, so none sends
 * an `Idempotency-Key`. Paging, order and filters are the server's: they travel as query parameters and
 * nothing here, or after it, sorts or filters a page in the browser.
 */
const BASE = "/api/v1/organization";

/** What every listing takes: `page` counts from 0, `sort` is `field` or `field,asc|desc`. */
export interface PageParams {
  readonly page: number;
  readonly size: number;
  readonly sort?: string | undefined;
}

export interface GradeListParams extends PageParams {
  readonly modalityId?: string | undefined;
}

export interface SectionListParams extends PageParams {
  readonly academicYearId?: string | undefined;
  readonly gradeId?: string | undefined;
}

const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });

function listPath(resource: string, params: object): string {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(params) as [string, unknown][]) {
    if (typeof value === "string" || typeof value === "number") query.set(name, String(value));
  }
  const text = query.toString();
  return `${BASE}/${resource}${text === "" ? "" : `?${text}`}`;
}

function list<T>(resource: string, schema: ResponseSchema<T>, token: string, params: object): Promise<ApiResult<T>> {
  return request({ method: "GET", path: listPath(resource, params), headers: bearer(token), schema });
}

function create<T>(resource: string, schema: ResponseSchema<T>, token: string, body: unknown): Promise<ApiResult<T>> {
  return request({ method: "POST", path: `${BASE}/${resource}`, headers: bearer(token), body, schema });
}

function update<T>(resource: string, schema: ResponseSchema<T>, token: string, id: string, body: unknown): Promise<ApiResult<T>> {
  return request({ method: "PUT", path: `${BASE}/${resource}/${encodeURIComponent(id)}`, headers: bearer(token), body, schema });
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
