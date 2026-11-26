import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { RejectScholarshipAwardBody, RequestAwardRequest, RevokeScholarshipAwardBody } from "@confia/contracts/admin";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import type { ApiResult } from "../../shared/api/http.ts";
import {
  approveAward,
  getAward,
  listAwards,
  rejectAward,
  requestAward,
  revokeAward,
  type Award,
  type AwardListParams,
} from "../../shared/api/scholarships-operations.ts";
import { awardKeys, scholarshipKeys } from "./scholarships-keys.ts";

/** One page of awards, as the server pages, filters and orders it. The page on screen stays until the next arrives. */
export function useAwardList(params: AwardListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: awardKeys.list(params),
    queryFn: () => send((token) => listAwards(token, params)),
    placeholderData: keepPreviousData,
  });
}

/** One award with its decision, or its revocation. */
export function useAwardDetail(id: string) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: awardKeys.detail(id),
    queryFn: () => send((token) => getAward(token, id)),
  });
}

/** The answers after which what is on screen is out of date: the award is no longer in the state it was shown in, or it does not exist. */
const STALE_CODES: ReadonlySet<string> = new Set(["invalid-award-state", "resource-not-found"]);

/**
 * What every write of the awards has in common. None moves money (an award only changes the charges issued from then on, and the ledger moves when
 * the accrual runs), so nothing is optimistic; the three decisions carry the `Idempotency-Key` of the intent, because the server stores it with the
 * decision. When the server confirms, the awards are read again (the detail and every list), and so are the scholarships (an approved award locks
 * the benefit of its scholarship). A simulation is never cached (each press asks the server again), so there is none to invalidate. When the server
 * says the award is no longer in the state the screen assumed, the same read happens, so the screen shows what is true.
 */
function useAwardWrite<Variables>(operation: (token: string, variables: Variables) => Promise<ApiResult<Award>>) {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<Award, Error, Variables>({
    mutationFn: (variables) => send((token) => operation(token, variables)),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: awardKeys.all }),
        queryClient.invalidateQueries({ queryKey: scholarshipKeys.all }),
      ]);
    },
    onError: async (error) => {
      if (error instanceof ApiFailure && error.result.kind === "problem" && STALE_CODES.has(error.result.problem.code)) {
        await queryClient.invalidateQueries({ queryKey: awardKeys.all });
      }
    },
  });
}

export interface DecisionVariables<Body> {
  readonly awardId: string;
  readonly body: Body;
  readonly idempotencyKey: string;
}

export const useAwardRequest = () => useAwardWrite<RequestAwardRequest>((token, body) => requestAward(token, body));

export const useApproveAward = () =>
  useAwardWrite<DecisionVariables<undefined>>((token, { awardId, idempotencyKey }) => approveAward(token, awardId, idempotencyKey));

export const useRejectAward = () =>
  useAwardWrite<DecisionVariables<RejectScholarshipAwardBody>>((token, { awardId, body, idempotencyKey }) => rejectAward(token, awardId, body, idempotencyKey));

export const useRevokeAward = () =>
  useAwardWrite<DecisionVariables<RevokeScholarshipAwardBody>>((token, { awardId, body, idempotencyKey }) => revokeAward(token, awardId, body, idempotencyKey));
