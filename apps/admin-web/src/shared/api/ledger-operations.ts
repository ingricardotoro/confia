import {
  LedgerAccountPageResponse,
  LedgerAccountResponse,
  PostingAccountMapResponse,
  type CreateLedgerAccountRequest,
  type LedgerAccountPageResponseOutput,
  type LedgerAccountResponseOutput,
  type PostingAccountMapResponseOutput,
  type SetPostingAccountsRequest,
  type UpdateLedgerAccountRequest,
} from "@confia/contracts/admin";
import { request, type ApiResult } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of the chart of accounts (`/api/v1/ledger/accounts`) and of the map of posting accounts
 * (`/api/v1/charges/posting-accounts`). Each takes the access token as a parameter: this module knows no session. None
 * is a route of money (they configure which account a charge is booked to, and book nothing), so none sends an
 * `Idempotency-Key`; the version of each record is the guard against a concurrent edit. Paging, order and filters are
 * the server's: they travel as query parameters and nothing here, or after it, sorts or filters a page in the browser.
 */
const { list, create, update } = resourceClient("/api/v1/ledger");

export type { PageParams };

export type LedgerAccountType = LedgerAccountResponseOutput["type"];
export type PostingPurpose = PostingAccountMapResponseOutput["items"][number]["purpose"];

export interface LedgerAccountListParams extends PageParams {
  readonly type?: LedgerAccountType | undefined;
  /** `"true"` or `"false"`: only strings and numbers travel in a query string. */
  readonly active?: "true" | "false" | undefined;
}

export const listLedgerAccounts = (token: string, params: LedgerAccountListParams): Promise<ApiResult<LedgerAccountPageResponseOutput>> =>
  list("accounts", LedgerAccountPageResponse, token, params);

/** A new account: it is born active, at version 0. */
export const createLedgerAccount = (token: string, body: CreateLedgerAccountRequest): Promise<ApiResult<LedgerAccountResponseOutput>> =>
  create("accounts", LedgerAccountResponse, token, body);

/** The name and the state of an account, with the version it was read at. The code, the type and the student flag never change. */
export const updateLedgerAccount = (token: string, id: string, body: UpdateLedgerAccountRequest): Promise<ApiResult<LedgerAccountResponseOutput>> =>
  update("accounts", LedgerAccountResponse, token, id, body);

const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });
const POSTING_ACCOUNTS = "/api/v1/charges/posting-accounts";

/** The map as it is now, with one row per entry that was ever set; a role that was never set has no row. */
export const getPostingAccounts = (token: string): Promise<ApiResult<PostingAccountMapResponseOutput>> =>
  request({ method: "GET", path: POSTING_ACCOUNTS, headers: bearer(token), schema: PostingAccountMapResponse });

/** A partial upsert: only the entries sent are written, each with the version of its row; the answer is the whole map. */
export const setPostingAccounts = (token: string, body: SetPostingAccountsRequest): Promise<ApiResult<PostingAccountMapResponseOutput>> =>
  request({ method: "PUT", path: POSTING_ACCOUNTS, headers: bearer(token), body, schema: PostingAccountMapResponse });
