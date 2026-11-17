import {
  ChargePlanDetailResponse,
  ChargePlanItemResponse,
  ChargePlanPageResponse,
  ChargePlanResponse,
  type AddChargePlanItemRequest,
  type ChargePlanDetailResponseOutput,
  type ChargePlanItemResponseOutput,
  type ChargePlanPageResponseOutput,
  type ChargePlanResponseOutput,
  type CreateChargePlanRequest,
  type UpdateChargePlanRequest,
} from "@confia/contracts/admin";
import { request, requestEmpty, type ApiResult } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/charges`. Each takes the access token as a parameter, like the operations of access: this
 * module knows no session. None moves money (a plan books nothing in the ledger and holds no price), so none sends an
 * `Idempotency-Key`. Paging, order and filters are the server's: they travel as query parameters and nothing here, or
 * after it, sorts or filters a page in the browser.
 */
const { list, create, update } = resourceClient("/api/v1/charges");

export type { PageParams };

export type ChargePlanScope = ChargePlanResponseOutput["targetScope"];
export type ChargePlanStatus = ChargePlanResponseOutput["status"];
export type ChargePlanFrequency = ChargePlanResponseOutput["frequency"];

export interface ChargePlanListParams extends PageParams {
  readonly academicYearId?: string | undefined;
  readonly targetScope?: ChargePlanScope | undefined;
  readonly status?: ChargePlanStatus | undefined;
}

const planPath = (id: string): string => `/api/v1/charges/plans/${encodeURIComponent(id)}`;
const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });

export const listChargePlans = (token: string, params: ChargePlanListParams): Promise<ApiResult<ChargePlanPageResponseOutput>> =>
  list("plans", ChargePlanPageResponse, token, params);

/** A new plan: it is born a draft. */
export const createChargePlan = (token: string, body: CreateChargePlanRequest): Promise<ApiResult<ChargePlanResponseOutput>> =>
  create("plans", ChargePlanResponse, token, body);

/** One plan with its items. */
export const getChargePlan = (token: string, id: string): Promise<ApiResult<ChargePlanDetailResponseOutput>> =>
  request({ method: "GET", path: planPath(id), headers: bearer(token), schema: ChargePlanDetailResponse });

/** The name, the frequency, the due day and the status of a plan, with the version it was read at. */
export const updateChargePlan = (token: string, id: string, body: UpdateChargePlanRequest): Promise<ApiResult<ChargePlanResponseOutput>> =>
  update("plans", ChargePlanResponse, token, id, body);

/** A new item of a draft plan: a concept, how many periods it accrues and the first day of the first one. */
export const addChargePlanItem = (token: string, planId: string, body: AddChargePlanItemRequest): Promise<ApiResult<ChargePlanItemResponseOutput>> =>
  request({ method: "POST", path: `${planPath(planId)}/items`, headers: bearer(token), body, schema: ChargePlanItemResponse });

/** Removes an item of a draft plan: the server answers `204` with no body. */
export const removeChargePlanItem = (token: string, planId: string, itemId: string): Promise<ApiResult<undefined>> =>
  requestEmpty({ method: "DELETE", path: `${planPath(planId)}/items/${encodeURIComponent(itemId)}`, headers: bearer(token) });
