import {
  PaymentPageResponse,
  PaymentPostingAccountMapResponse,
  PaymentResponse,
  type PaymentPageResponseOutput,
  type PaymentPostingAccountMapResponseOutput,
  type PaymentResponseOutput,
  type RegisterPaymentRequest,
  type RejectPaymentRequest,
  type SetPaymentPostingAccountsRequest,
} from "@confia/contracts/admin";
import { lenient } from "./cashbox-operations.ts";
import { request, type ApiResult } from "./http.ts";
import type { PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/payments` (payments-register cut). Each takes the access token as a parameter: this module knows no
 * session. The three writes that move money (register, confirm, reject) carry the `Idempotency-Key` of the person's intent. Every
 * amount travels as a `{ amount, currency }` string the server computed and rounded (what was allocated, what stayed in favor):
 * nothing here, or after it, adds, splits or rounds one. Paging, order and filters are the server's and travel as query parameters.
 */
export type { PageParams };

export type Payment = PaymentResponseOutput;
export type PaymentPage = PaymentPageResponseOutput;
export type PaymentMethod = PaymentResponseOutput["method"];
export type PaymentStatus = PaymentResponseOutput["status"];
export type PaymentPostingAccountMap = PaymentPostingAccountMapResponseOutput;
export type PaymentPurpose = PaymentPostingAccountMapResponseOutput["items"][number]["purpose"];

export interface PaymentListParams extends PageParams {
  readonly status?: PaymentStatus | undefined;
  readonly method?: PaymentMethod | undefined;
  /** A student. */
  readonly studentId?: string | undefined;
  /** `yyyy-MM-dd`: payments received from this day included. */
  readonly from?: string | undefined;
  /** `yyyy-MM-dd`: payments received up to this day included. */
  readonly to?: string | undefined;
}

const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });
const withKey = (token: string, idempotencyKey: string): Readonly<Record<string, string>> => ({ ...bearer(token), "Idempotency-Key": idempotencyKey });
const paymentPath = (id: string): string => `/api/v1/payments/${encodeURIComponent(id)}`;
const POSTING_ACCOUNTS = "/api/v1/payments/posting-accounts";

/** One page of payments, as the server pages, filters and orders it (newest registered first when no order is asked). */
export const listPayments = (token: string, params: PaymentListParams): Promise<ApiResult<PaymentPage>> => {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(params) as [string, unknown][]) {
    if (typeof value === "string" || typeof value === "number") query.set(name, String(value));
  }
  return request({ method: "GET", path: `/api/v1/payments?${query.toString()}`, headers: bearer(token), schema: lenient(PaymentPageResponse) });
};

/** One payment with its allocations, its decision and the sum that stayed in favor of the student. */
export const getPayment = (token: string, id: string): Promise<ApiResult<Payment>> =>
  request({ method: "GET", path: paymentPath(id), headers: bearer(token), schema: lenient(PaymentResponse) });

/** Registers a payment: cash is posted and allocated at once, a transfer waits for its confirmation. */
export const registerPayment = (token: string, body: RegisterPaymentRequest, idempotencyKey: string): Promise<ApiResult<Payment>> =>
  request({ method: "POST", path: "/api/v1/payments", headers: withKey(token, idempotencyKey), body, schema: lenient(PaymentResponse) });

/** Posts and allocates a declared transfer. The server refuses the person who registered it. */
export const confirmPayment = (token: string, id: string, idempotencyKey: string): Promise<ApiResult<Payment>> =>
  request({ method: "POST", path: `${paymentPath(id)}/confirm`, headers: withKey(token, idempotencyKey), schema: lenient(PaymentResponse) });

/** Rejects a declared transfer: nothing is posted, ever. */
export const rejectPayment = (token: string, id: string, body: RejectPaymentRequest, idempotencyKey: string): Promise<ApiResult<Payment>> =>
  request({ method: "POST", path: `${paymentPath(id)}/reject`, headers: withKey(token, idempotencyKey), body, schema: lenient(PaymentResponse) });

/** The cash and bank accounts the payments post to, with one row per entry ever set. */
export const getPaymentPostingAccounts = (token: string): Promise<ApiResult<PaymentPostingAccountMap>> =>
  request({ method: "GET", path: POSTING_ACCOUNTS, headers: bearer(token), schema: PaymentPostingAccountMapResponse });

/** A partial upsert: only the entries sent are written, each with the version of its row; the answer is the whole map. */
export const setPaymentPostingAccounts = (token: string, body: SetPaymentPostingAccountsRequest): Promise<ApiResult<PaymentPostingAccountMap>> =>
  request({ method: "PUT", path: POSTING_ACCOUNTS, headers: bearer(token), body, schema: PaymentPostingAccountMapResponse });
