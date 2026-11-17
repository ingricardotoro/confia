import {
  PaymentConceptPageResponse,
  PaymentConceptResponse,
  PriceListDetailResponse,
  PriceListItemResponse,
  PriceListPageResponse,
  PriceListResponse,
  TaxRateDetailResponse,
  TaxRatePageResponse,
  TaxRatePeriodResponse,
  TaxRateResponse,
  type AddPriceRequest,
  type AddTaxRatePeriodRequest,
  type CreatePaymentConceptRequest,
  type CreatePriceListRequest,
  type CreateTaxRateRequest,
  type PaymentConceptPageResponseOutput,
  type PaymentConceptResponseOutput,
  type PriceListDetailResponseOutput,
  type PriceListItemResponseOutput,
  type PriceListPageResponseOutput,
  type PriceListResponseOutput,
  type TaxRateDetailResponseOutput,
  type TaxRatePageResponseOutput,
  type TaxRatePeriodResponseOutput,
  type TaxRateResponseOutput,
  type UpdatePaymentConceptRequest,
  type UpdatePriceListRequest,
  type UpdateTaxRateRequest,
} from "@confia/contracts/admin";
import { request, type ApiResult } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/catalog`. Each takes the access token as a parameter, like the operations of access: this
 * module knows no session. None moves money (setting a price books nothing in the ledger), so none sends an
 * `Idempotency-Key`. An amount is the string the server gave or the person typed: nothing here parses it. Paging,
 * order and filters are the server's: they travel as query parameters and nothing here, or after it, sorts or filters
 * a page in the browser.
 */
const { list, create, update } = resourceClient("/api/v1/catalog");

export type { PageParams };

export type PaymentCategory = PaymentConceptResponseOutput["category"];

export interface PaymentConceptListParams extends PageParams {
  readonly category?: PaymentCategory | undefined;
  /** `"true"` or `"false"`: only strings and numbers travel in a query string. */
  readonly active?: "true" | "false" | undefined;
}

export const listPaymentConcepts = (token: string, params: PaymentConceptListParams): Promise<ApiResult<PaymentConceptPageResponseOutput>> =>
  list("payment-concepts", PaymentConceptPageResponse, token, params);

export const createPaymentConcept = (token: string, body: CreatePaymentConceptRequest): Promise<ApiResult<PaymentConceptResponseOutput>> =>
  create("payment-concepts", PaymentConceptResponse, token, body);

export const updatePaymentConcept = (
  token: string,
  id: string,
  body: UpdatePaymentConceptRequest,
): Promise<ApiResult<PaymentConceptResponseOutput>> => update("payment-concepts", PaymentConceptResponse, token, id, body);

export type PriceListScope = PriceListResponseOutput["scope"];
export type PriceListStatus = PriceListResponseOutput["status"];

export interface PriceListListParams extends PageParams {
  readonly academicYearId?: string | undefined;
  readonly scope?: PriceListScope | undefined;
  readonly status?: PriceListStatus | undefined;
}

const priceListPath = (id: string): string => `/api/v1/catalog/price-lists/${encodeURIComponent(id)}`;

export const listPriceLists = (token: string, params: PriceListListParams): Promise<ApiResult<PriceListPageResponseOutput>> =>
  list("price-lists", PriceListPageResponse, token, params);

export const createPriceList = (token: string, body: CreatePriceListRequest): Promise<ApiResult<PriceListResponseOutput>> =>
  create("price-lists", PriceListResponse, token, body);

/** One list with every price it has ever held: the closed validities and the open one. */
export const getPriceList = (token: string, id: string): Promise<ApiResult<PriceListDetailResponseOutput>> =>
  request({ method: "GET", path: priceListPath(id), headers: { Authorization: `Bearer ${token}` }, schema: PriceListDetailResponse });

export const updatePriceList = (token: string, id: string, body: UpdatePriceListRequest): Promise<ApiResult<PriceListResponseOutput>> =>
  update("price-lists", PriceListResponse, token, id, body);

/** A new price, or a new validity of a concept that already has one: the server closes the previous open validity. */
export const addPrice = (token: string, listId: string, body: AddPriceRequest): Promise<ApiResult<PriceListItemResponseOutput>> =>
  request({ method: "POST", path: `${priceListPath(listId)}/items`, headers: { Authorization: `Bearer ${token}` }, body, schema: PriceListItemResponse });

export interface TaxRateListParams extends PageParams {
  /** `"true"` or `"false"`: only strings and numbers travel in a query string. */
  readonly active?: "true" | "false" | undefined;
}

const taxRatePath = (id: string): string => `/api/v1/catalog/tax-rates/${encodeURIComponent(id)}`;

export const listTaxRates = (token: string, params: TaxRateListParams): Promise<ApiResult<TaxRatePageResponseOutput>> =>
  list("tax-rates", TaxRatePageResponse, token, params);

export const createTaxRate = (token: string, body: CreateTaxRateRequest): Promise<ApiResult<TaxRateResponseOutput>> =>
  create("tax-rates", TaxRateResponse, token, body);

/** One tax rate with every validity it has ever had: the closed ones and the open one. */
export const getTaxRate = (token: string, id: string): Promise<ApiResult<TaxRateDetailResponseOutput>> =>
  request({ method: "GET", path: taxRatePath(id), headers: { Authorization: `Bearer ${token}` }, schema: TaxRateDetailResponse });

export const updateTaxRate = (token: string, id: string, body: UpdateTaxRateRequest): Promise<ApiResult<TaxRateResponseOutput>> =>
  update("tax-rates", TaxRateResponse, token, id, body);

/**
 * A new validity of a tax rate: the server closes the previous open one the day before. The percentage is the text the
 * person typed (`ratePercent`): nothing here parses it, and the server answers with it at four decimals.
 */
export const addTaxRatePeriod = (token: string, rateId: string, body: AddTaxRatePeriodRequest): Promise<ApiResult<TaxRatePeriodResponseOutput>> =>
  request({ method: "POST", path: `${taxRatePath(rateId)}/periods`, headers: { Authorization: `Bearer ${token}` }, body, schema: TaxRatePeriodResponse });
