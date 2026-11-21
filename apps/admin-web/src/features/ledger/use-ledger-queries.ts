import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import type { PostingAccountMapResponseOutput, SetPaymentPostingAccountsRequest, SetPostingAccountsRequest } from "@confia/contracts/admin";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import {
  getPostingAccounts,
  listLedgerAccounts,
  setPostingAccounts,
  type LedgerAccountListParams,
  type LedgerAccountType,
} from "../../shared/api/ledger-operations.ts";
import { getPaymentPostingAccounts, setPaymentPostingAccounts, type PaymentPostingAccountMap } from "../../shared/api/payments-operations.ts";
import { LOOKUP_PAGE_SIZE } from "../../shared/organization-lookups/use-organization-queries.ts";
import type { Lookup } from "../../shared/organization-lookups/use-lookups.ts";
import { writeAccount, type LedgerAccount, type AccountSave } from "./account-form.ts";
import { ledgerAccountKeys, paymentPostingAccountKeys, postingAccountKeys } from "./ledger-keys.ts";

/** A listing of accounts, as the server pages, orders and filters it: the rows come back as they were served. */
export function useLedgerAccountList(params: LedgerAccountListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: ledgerAccountKeys.list(params),
    queryFn: () => send((token) => listLedgerAccounts(token, params)),
  });
}

/** The 409 codes that say what is on screen is no longer what the server holds. */
const STALE_CODES: ReadonlySet<string> = new Set(["stale-version"]);
/** The refusals of the map that say the accounts on screen are no longer what the server holds. */
const MAP_REFRESH_CODES: ReadonlySet<string> = new Set(["stale-version", "resource-not-found", "inactive-reference", "posting-account-invalid"]);

function refreshOnConflict(queryClient: QueryClient, error: unknown, queryKeys: readonly (readonly unknown[])[], codes: ReadonlySet<string>): void {
  if (!(error instanceof ApiFailure) || error.result.kind !== "problem" || !codes.has(error.result.problem.code)) return;
  for (const queryKey of queryKeys) void queryClient.invalidateQueries({ queryKey });
}

/**
 * A write of one account. It books nothing, so nothing is optimistic and no `Idempotency-Key` is sent, but it never
 * repeats by itself (the app's mutations do not retry). After a write every listing of accounts is invalidated: the server is
 * the truth about what is listed, and the selects of the map read the same keys. A stale version refreshes them too.
 */
export function useLedgerAccountWrite() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<LedgerAccount, Error, AccountSave>({
    mutationFn: (save) => send((token) => writeAccount(token, save)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ledgerAccountKeys.all }),
    onError: (error) => {
      refreshOnConflict(queryClient, error, [ledgerAccountKeys.all], STALE_CODES);
    },
  });
}

/** The map of posting accounts, as the server holds it. */
export function usePostingAccounts() {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: postingAccountKeys.map(),
    queryFn: () => send((token) => getPostingAccounts(token)),
  });
}

/**
 * The save of the map: only the entries that changed, each with the version of its row, in one request that the server
 * saves whole or not at all. Nothing is optimistic. After it the map is read again; a refusal that says the screen is out of
 * date (a stale version, an account that is gone, inactive or of the wrong type) reads the map and the accounts again too.
 */
export function usePostingAccountsSave() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<PostingAccountMapResponseOutput, Error, SetPostingAccountsRequest>({
    mutationFn: (body) => send((token) => setPostingAccounts(token, body)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: postingAccountKeys.all }),
    onError: (error) => {
      refreshOnConflict(queryClient, error, [postingAccountKeys.all, ledgerAccountKeys.all], MAP_REFRESH_CODES);
    },
  });
}

/** The map of the accounts the payments post to (the cash and the bank), as the server holds it. */
export function usePaymentPostingAccounts() {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: paymentPostingAccountKeys.map(),
    queryFn: () => send((token) => getPaymentPostingAccounts(token)),
  });
}

/** The save of the cash and bank accounts: only the entries that changed, each with the version of its row. Nothing is optimistic; the map is read again after it. */
export function usePaymentPostingAccountsSave() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<PaymentPostingAccountMap, Error, SetPaymentPostingAccountsRequest>({
    mutationFn: (body) => send((token) => setPaymentPostingAccounts(token, body)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: paymentPostingAccountKeys.all }),
    onError: (error) => {
      refreshOnConflict(queryClient, error, [paymentPostingAccountKeys.all, ledgerAccountKeys.all], MAP_REFRESH_CODES);
    },
  });
}

/**
 * The active accounts of one type, for a select of the map: one page of the largest size the API gives, in the order of the
 * code. It shares its key with the list of the chart, so a write there refreshes it. A lookup is a label and an option, never
 * a filter of a table.
 */
export function useActiveAccountLookup(type: LedgerAccountType): Lookup<LedgerAccount> {
  const send = useAuthorizedSend();
  const params: LedgerAccountListParams = { page: 0, size: LOOKUP_PAGE_SIZE, sort: "code,asc", type, active: "true" };
  const list = useQuery({
    queryKey: ledgerAccountKeys.list(params),
    queryFn: () => send((token) => listLedgerAccounts(token, params)),
  });
  return { rows: list.data?.items ?? [], failed: list.isError, loading: list.isPending, retry: () => void list.refetch() };
}
