import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import type { AddPriceRequest, CreatePriceListRequest, UpdatePriceListRequest } from "@confia/contracts/admin";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import {
  addPrice,
  createPriceList,
  getPriceList,
  listPaymentConcepts,
  listPriceLists,
  updatePriceList,
  type PaymentConceptListParams,
  type PriceListListParams,
} from "../../shared/api/catalog-operations.ts";
import { LOOKUP_PAGE_SIZE } from "../../shared/organization-lookups/use-organization-queries.ts";
import { paymentConceptKeys, priceListKeys } from "./catalog-keys.ts";
import { writePaymentConcept, type PaymentConcept, type PaymentConceptSave } from "./payment-concept-form.ts";
import type { PriceItem } from "./price-form.ts";
import type { PriceList } from "./price-list-form.ts";

/** A listing of payment concepts, as the server pages, orders and filters it: the rows come back as they were served. */
export function usePaymentConceptList(params: PaymentConceptListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: paymentConceptKeys.list(params),
    queryFn: () => send((token) => listPaymentConcepts(token, params)),
  });
}

/** The 409 codes that say what is on screen is no longer what the server holds. */
const STALE_CODES: ReadonlySet<string> = new Set(["stale-version"]);
const CONFLICT_CODES: ReadonlySet<string> = new Set(["stale-version", "price-validity-overlap", "price-list-archived"]);

/**
 * When a write is refused because the screen is out of date (a stale version, or a validity or an archive someone else
 * made), the queries are read again: the server is the truth about what is there and at which version.
 */
function refreshOnConflict(queryClient: QueryClient, error: unknown, queryKey: readonly unknown[], codes: ReadonlySet<string>): void {
  if (!(error instanceof ApiFailure) || error.result.kind !== "problem" || !codes.has(error.result.problem.code)) return;
  void queryClient.invalidateQueries({ queryKey });
}

/**
 * A write of one payment concept. It is not financial (a concept has no price), so nothing is optimistic and no
 * `Idempotency-Key` is sent, but it never repeats by itself (the app's mutations do not retry). After a write every
 * list of the catalog is invalidated: the server is the truth about what is listed. A stale version refreshes them too,
 * so the next open of the record carries the version the server holds.
 */
export function usePaymentConceptWrite() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<PaymentConcept, Error, PaymentConceptSave>({
    mutationFn: (save) => send((token) => writePaymentConcept(token, save)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: paymentConceptKeys.all }),
    onError: (error) => {
      refreshOnConflict(queryClient, error, paymentConceptKeys.all, STALE_CODES);
    },
  });
}

/**
 * The concepts a price can name, for the select of the form and for the names in the detail: one page of the largest
 * size the API gives, in the server's order. A lookup is a label and an option, never a filter of a table.
 */
export function usePaymentConceptLookup() {
  const list = usePaymentConceptList({ page: 0, size: LOOKUP_PAGE_SIZE, sort: "name,asc" });
  return { rows: list.data?.items ?? [], failed: list.isError, loading: list.isPending, retry: () => void list.refetch() };
}

/** A listing of price lists, as the server pages, orders and filters it. */
export function usePriceListList(params: PriceListListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: priceListKeys.list(params),
    queryFn: () => send((token) => listPriceLists(token, params)),
  });
}

/** One price list with every validity of every price it holds. */
export function usePriceListDetail(id: string) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: priceListKeys.detail(id),
    queryFn: () => send((token) => getPriceList(token, id)),
  });
}

/** The creation of a list. Nothing is optimistic: every list of the catalog is read again after it. */
export function usePriceListCreate() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<PriceList, Error, CreatePriceListRequest>({
    mutationFn: (body) => send((token) => createPriceList(token, body)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: priceListKeys.all }),
  });
}

interface PriceListUpdate {
  readonly id: string;
  readonly body: UpdatePriceListRequest;
}

/**
 * The archive or reactivation of a list, with the version it was read at. After it, the detail of the list and every
 * listing are read again; so are they after a stale version, which says the list is no longer what was on screen.
 */
export function usePriceListUpdate() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<PriceList, Error, PriceListUpdate>({
    mutationFn: ({ id, body }) => send((token) => updatePriceList(token, id, body)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: priceListKeys.all }),
    onError: (error) => {
      refreshOnConflict(queryClient, error, priceListKeys.all, STALE_CODES);
    },
  });
}

interface PriceAdd {
  readonly listId: string;
  readonly body: AddPriceRequest;
}

/**
 * A new price or a new validity. Setting a price books nothing in the ledger, so there is no `Idempotency-Key`; it is
 * still never optimistic and never repeats by itself. The server closes the previous open validity in the same
 * transaction, so the whole detail is read again, and the listings with it. A refused add (overlap, archived list,
 * stale version) changes nothing on the server, and reads the detail again because the screen was out of date.
 */
export function usePriceAdd() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<PriceItem, Error, PriceAdd>({
    mutationFn: ({ listId, body }) => send((token) => addPrice(token, listId, body)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: priceListKeys.all }),
    onError: (error) => {
      refreshOnConflict(queryClient, error, priceListKeys.all, CONFLICT_CODES);
    },
  });
}
