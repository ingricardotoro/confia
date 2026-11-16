import {
  PaymentConceptPageResponse,
  PaymentConceptResponse,
  type CreatePaymentConceptRequest,
  type PaymentConceptPageResponseOutput,
  type PaymentConceptResponseOutput,
  type UpdatePaymentConceptRequest,
} from "@confia/contracts/admin";
import type { ApiResult } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/catalog`. Each takes the access token as a parameter, like the operations of access: this
 * module knows no session. None moves money (a concept has no price yet), so none sends an `Idempotency-Key`. Paging,
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
