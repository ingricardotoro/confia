import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ApiResult } from "../../shared/api/http.ts";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import { organizationKeys, type OrganizationResource } from "./organization-keys.ts";

/** The most a lookup list asks for: the API's own limit per page. */
export const LOOKUP_PAGE_SIZE = 200;

interface Page<Row> {
  readonly items: readonly Row[];
  readonly total: number;
  readonly page: number;
  readonly size: number;
}

/**
 * A listing of one resource, as the server pages, orders and filters it: `params` go to the API as they are and the
 * rows come back as they were served. It does not retry by itself beyond the app's rule for reads.
 */
export function useResourceList<Row, Params extends object>(
  resource: OrganizationResource,
  params: Params,
  fetchPage: (token: string, params: Params) => Promise<ApiResult<Page<Row>>>,
) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: organizationKeys.list(resource, params),
    queryFn: () => send((token) => fetchPage(token, params)),
  });
}

/**
 * A write of one resource. It is not financial, so nothing is optimistic and no `Idempotency-Key` is sent, but it
 * never repeats by itself (the app's mutations do not retry). After a write the whole area is invalidated: a
 * renamed year or modality is also a label in the lists of grades and sections, and a lookup list is a list.
 */
export function useResourceWrite<Variables, Data>(operation: (token: string, variables: Variables) => Promise<ApiResult<Data>>) {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: Variables) => send((token) => operation(token, variables)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: organizationKeys.all }),
  });
}
