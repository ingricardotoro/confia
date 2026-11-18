import { useQuery } from "@tanstack/react-query";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import { getIntegrityRun, getLatestIntegrityRun, listIntegrityRuns, type IntegrityRunsParams } from "../../shared/api/charges-operations.ts";
import { integrityKeys } from "./charges-keys.ts";

/** One page of the nightly runs, newest first as the server orders them. */
export function useIntegrityRunList(params: IntegrityRunsParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: integrityKeys.list(params),
    queryFn: () => send((token) => listIntegrityRuns(token, params)),
  });
}

/** The latest run with its discrepancies, for the banner. A 404 means the check has not run yet. */
export function useLatestIntegrityRun() {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: integrityKeys.latest(),
    queryFn: () => send((token) => getLatestIntegrityRun(token)),
    // A missing run is an answer, not a failure worth three tries.
    retry: false,
  });
}

/** One run with its discrepancies. */
export function useIntegrityRunDetail(runId: string) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: integrityKeys.detail(runId),
    queryFn: () => send((token) => getIntegrityRun(token, runId)),
  });
}
