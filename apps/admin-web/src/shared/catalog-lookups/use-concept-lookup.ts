import { useQuery } from "@tanstack/react-query";
import { listPaymentConcepts } from "../api/catalog-operations.ts";
import { useAuthorizedSend } from "../api/authorized-send.tsx";
import { LOOKUP_PAGE_SIZE } from "../organization-lookups/use-organization-queries.ts";
import type { Lookup } from "../organization-lookups/use-lookups.ts";
import { paymentConceptKeys } from "../query-keys/payment-concept-keys.ts";

/** What a screen of another area needs of a payment concept: to name it, and to offer it only while it is active. */
export interface ConceptLookupRow {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly isActive: boolean;
}

/**
 * The payment concepts a screen names or offers: one page of the largest size the API gives, in the order of the name.
 * It shares its key with the list of the catalog, so a write there refreshes it. A lookup is a label and an option,
 * never a filter of a table.
 */
export function useConceptLookup(): Lookup<ConceptLookupRow> {
  const send = useAuthorizedSend();
  const params = { page: 0, size: LOOKUP_PAGE_SIZE, sort: "name,asc" };
  const list = useQuery({
    queryKey: paymentConceptKeys.list(params),
    queryFn: () => send((token) => listPaymentConcepts(token, params)),
  });
  return { rows: list.data?.items ?? [], failed: list.isError, loading: list.isPending, retry: () => void list.refetch() };
}
