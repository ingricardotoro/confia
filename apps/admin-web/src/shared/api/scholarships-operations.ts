import {
  AwardPageResponse,
  AwardResponse,
  ScholarshipPageResponse,
  ScholarshipResponse,
  type AwardPageResponseOutput,
  type AwardResponseOutput,
  type CreateScholarshipRequest,
  type RejectScholarshipAwardBody,
  type RequestAwardRequest,
  type RevokeScholarshipAwardBody,
  type ScholarshipPageResponseOutput,
  type ScholarshipResponseOutput,
  type UpdateScholarshipRequest,
} from "@confia/contracts/admin";
import { request, type ApiResult } from "./http.ts";
import type { PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/scholarships` and `/api/v1/scholarship-awards` (scholarships-awards cut). Each takes the access token as a
 * parameter: this module knows no session. Defining a scholarship and requesting an award book nothing in the ledger, so they send no
 * `Idempotency-Key`; the three decisions on an award (approve, reject, revoke) are permanent decisions of the person and carry the key of their
 * intent. An amount is the string the server gave or the person typed: nothing here parses, adds or rounds it. Paging, order and filters are the
 * server's: they travel as query parameters and nothing here, or after it, sorts or filters a page in the browser.
 */
const SCHOLARSHIPS = "/api/v1/scholarships";
const AWARDS = "/api/v1/scholarship-awards";

export type { PageParams };

export type Scholarship = ScholarshipResponseOutput;
export type ScholarshipPage = ScholarshipPageResponseOutput;
export type BenefitType = ScholarshipResponseOutput["benefitType"];
export type Award = AwardResponseOutput;
export type AwardPage = AwardPageResponseOutput;
export type AwardStatus = AwardResponseOutput["status"];

const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });
const withKey = (token: string, idempotencyKey: string): Readonly<Record<string, string>> => ({ ...bearer(token), "Idempotency-Key": idempotencyKey });
const scholarshipPath = (id: string): string => `${SCHOLARSHIPS}/${encodeURIComponent(id)}`;
const awardPath = (id: string): string => `${AWARDS}/${encodeURIComponent(id)}`;

/** The query string of a listing: only strings and numbers travel, so a filter that is absent is not sent. */
function queryOf(params: object): string {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(params) as [string, unknown][]) {
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") query.set(name, String(value));
  }
  const text = query.toString();
  return text === "" ? "" : `?${text}`;
}

export interface ScholarshipListParams extends PageParams {
  readonly benefitType?: BenefitType | undefined;
  /** Only the active or only the inactive ones; absent: all. */
  readonly active?: boolean | undefined;
}

export const listScholarships = (token: string, params: ScholarshipListParams): Promise<ApiResult<ScholarshipPage>> =>
  request({ method: "GET", path: `${SCHOLARSHIPS}${queryOf(params)}`, headers: bearer(token), schema: ScholarshipPageResponse });

export const createScholarship = (token: string, body: CreateScholarshipRequest): Promise<ApiResult<Scholarship>> =>
  request({ method: "POST", path: SCHOLARSHIPS, headers: bearer(token), body, schema: ScholarshipResponse });

/** The name, the cap and the concepts of a scholarship, and its value while no award has been approved; with the version it was read at. */
export const updateScholarship = (token: string, id: string, body: UpdateScholarshipRequest): Promise<ApiResult<Scholarship>> =>
  request({ method: "PUT", path: scholarshipPath(id), headers: bearer(token), body, schema: ScholarshipResponse });

/** A scholarship is never deleted: it stops being offered and stops applying to the charges to come. */
export const deactivateScholarship = (token: string, id: string): Promise<ApiResult<Scholarship>> =>
  request({ method: "POST", path: `${scholarshipPath(id)}/deactivate`, headers: bearer(token), schema: ScholarshipResponse });

export const reactivateScholarship = (token: string, id: string): Promise<ApiResult<Scholarship>> =>
  request({ method: "POST", path: `${scholarshipPath(id)}/reactivate`, headers: bearer(token), schema: ScholarshipResponse });

export interface AwardListParams extends PageParams {
  readonly status?: AwardStatus | undefined;
  readonly scholarshipId?: string | undefined;
  readonly studentId?: string | undefined;
  readonly academicYearId?: string | undefined;
}

export const listAwards = (token: string, params: AwardListParams): Promise<ApiResult<AwardPage>> =>
  request({ method: "GET", path: `${AWARDS}${queryOf(params)}`, headers: bearer(token), schema: AwardPageResponse });

export const getAward = (token: string, id: string): Promise<ApiResult<Award>> =>
  request({ method: "GET", path: awardPath(id), headers: bearer(token), schema: AwardResponse });

/** The request of an award: it is born pending, and another person decides it. */
export const requestAward = (token: string, body: RequestAwardRequest): Promise<ApiResult<Award>> =>
  request({ method: "POST", path: AWARDS, headers: bearer(token), body, schema: AwardResponse });

/** Approves a pending award. The server refuses the person who requested it. */
export const approveAward = (token: string, id: string, idempotencyKey: string): Promise<ApiResult<Award>> =>
  request({ method: "POST", path: `${awardPath(id)}/approve`, headers: withKey(token, idempotencyKey), schema: AwardResponse });

/** Rejects a pending award, with the reason (20 to 500 characters). */
export const rejectAward = (token: string, id: string, body: RejectScholarshipAwardBody, idempotencyKey: string): Promise<ApiResult<Award>> =>
  request({ method: "POST", path: `${awardPath(id)}/reject`, headers: withKey(token, idempotencyKey), body, schema: AwardResponse });

/** Revokes an approved award, with the reason (20 to 500 characters): it only affects the charges to come. */
export const revokeAward = (token: string, id: string, body: RevokeScholarshipAwardBody, idempotencyKey: string): Promise<ApiResult<Award>> =>
  request({ method: "POST", path: `${awardPath(id)}/revoke`, headers: withKey(token, idempotencyKey), body, schema: AwardResponse });
