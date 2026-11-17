import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import {
  getAccrualRun,
  listAccrualPeriods,
  listAccrualRuns,
  previewAccrual,
  requestAccrualRun,
  type AccrualItemsParams,
  type AccrualPreview,
  type AccrualRun,
  type AccrualRunListParams,
} from "../../shared/api/charges-operations.ts";
import { pollIntervalOf, RUN_POLL_MS, runBodyOf, type AccrualIntent, type AccrualSelection } from "./accrual-model.ts";
import { accrualKeys, chargePlanKeys, statementKeys } from "./charges-keys.ts";

/** The periods a plan accrues, with the most recent run of each, as the server derives them. Nothing is read until a plan is chosen. */
export function useAccrualPeriods(planId: string | undefined) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: accrualKeys.periods(planId ?? ""),
    queryFn: () => send((token) => listAccrualPeriods(token, planId ?? "")),
    enabled: planId !== undefined,
  });
}

/**
 * The preview of a period. It is a calculation the server does and writes nothing, so it is not cached: every press asks again,
 * and its answer lives only as long as the choice it was made for. Repeating it is safe and sends no `Idempotency-Key`.
 */
export function useAccrualPreview() {
  const send = useAuthorizedSend();
  return useMutation<AccrualPreview, Error, AccrualSelection>({
    mutationFn: ({ planId, periodStart }) => send((token) => previewAccrual(token, planId, { periodStart })),
  });
}

/**
 * The request of a run. It writes the ledger (through the worker), so nothing is optimistic and the `Idempotency-Key` of the
 * intent travels with every try of it. When the server accepts it, everything the engine shows is read again: the periods of the
 * plan (their last run), every list of runs and every run. The statement of account, the balance and the dashboard figures of
 * the students do not exist yet; when they do, their prefixes are invalidated here too.
 */
export function useAccrualRunRequest() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<AccrualRun, Error, AccrualIntent>({
    mutationFn: (intent) => send((token) => requestAccrualRun(token, intent.planId, runBodyOf(intent), intent.idempotencyKey)),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: accrualKeys.all }),
        queryClient.invalidateQueries({ queryKey: chargePlanKeys.all }),
        // A run books charges in the ledger, so every statement of account is read again.
        queryClient.invalidateQueries({ queryKey: statementKeys.all }),
      ]);
    },
  });
}

/** One page of runs, as the server orders and filters it (newest first). A list that holds a run still going is read again by itself. */
export function useAccrualRunList(params: AccrualRunListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: accrualKeys.runList(params),
    queryFn: () => send((token) => listAccrualRuns(token, params)),
    refetchInterval: (query) => (query.state.data?.items.some((run) => pollIntervalOf(run.status) !== false) === true ? RUN_POLL_MS : false),
  });
}

/**
 * One run with a page of its items. While the run is queued or running the query reads it again every few seconds, and it stops by
 * itself at the first terminal state; a run that is already terminal when the page opens is read once.
 */
export function useAccrualRunDetail(runId: string, params: AccrualItemsParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: accrualKeys.runDetail(runId, params),
    queryFn: () => send((token) => getAccrualRun(token, runId, params)),
    refetchInterval: (query) => pollIntervalOf(query.state.data?.run.status),
  });
}
