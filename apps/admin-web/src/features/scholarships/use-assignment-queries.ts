import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { RejectDiscountAssignmentBody, RequestDiscountAssignmentRequest, RevokeDiscountAssignmentBody } from "@confia/contracts/admin";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import type { ApiResult } from "../../shared/api/http.ts";
import {
  approveDiscountAssignment,
  getDiscountAssignment,
  listDiscountAssignments,
  rejectDiscountAssignment,
  requestDiscountAssignment,
  revokeDiscountAssignment,
  type DiscountAssignment,
  type DiscountAssignmentListParams,
} from "../../shared/api/scholarships-operations.ts";
import { assignmentKeys, discountKeys } from "./scholarships-keys.ts";
import type { DecisionVariables } from "./use-award-queries.ts";

/** One page of assignments, as the server pages, filters and orders it. The page on screen stays until the next arrives. */
export function useAssignmentList(params: DiscountAssignmentListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: assignmentKeys.list(params),
    queryFn: () => send((token) => listDiscountAssignments(token, params)),
    placeholderData: keepPreviousData,
  });
}

/** One assignment with its decision, or its revocation. */
export function useAssignmentDetail(id: string) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: assignmentKeys.detail(id),
    queryFn: () => send((token) => getDiscountAssignment(token, id)),
  });
}

/**
 * The answers after which what is on screen is out of date: the assignment is no longer in the state it was shown in, its discount changed after it was
 * requested (`discount-terms-changed`: it is rejected and requested again), or it does not exist.
 */
const STALE_CODES: ReadonlySet<string> = new Set(["invalid-discount-assignment-state", "discount-terms-changed", "resource-not-found"]);

/**
 * What every write of the assignments has in common. None moves money (an assignment only changes the charges issued from then on, and the ledger moves
 * when the accrual runs), so nothing is optimistic; the three decisions carry the `Idempotency-Key` of the intent, because the server stores it with the
 * decision. When the server confirms, the assignments are read again (the detail and every list), and so are the discounts (an approved assignment puts
 * its discount in use). When the server says the assignment is no longer in the state the screen assumed, the same read happens, so the screen shows
 * what is true.
 */
function useAssignmentWrite<Variables>(operation: (token: string, variables: Variables) => Promise<ApiResult<DiscountAssignment>>) {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<DiscountAssignment, Error, Variables>({
    mutationFn: (variables) => send((token) => operation(token, variables)),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: assignmentKeys.all }),
        queryClient.invalidateQueries({ queryKey: discountKeys.all }),
      ]);
    },
    onError: async (error) => {
      if (error instanceof ApiFailure && error.result.kind === "problem" && STALE_CODES.has(error.result.problem.code)) {
        await queryClient.invalidateQueries({ queryKey: assignmentKeys.all });
      }
    },
  });
}

export interface AssignmentDecisionVariables<Body> extends Omit<DecisionVariables<Body>, "awardId"> {
  readonly assignmentId: string;
}

export const useAssignmentRequest = () => useAssignmentWrite<RequestDiscountAssignmentRequest>((token, body) => requestDiscountAssignment(token, body));

export const useApproveAssignment = () =>
  useAssignmentWrite<AssignmentDecisionVariables<undefined>>((token, { assignmentId, idempotencyKey }) => approveDiscountAssignment(token, assignmentId, idempotencyKey));

export const useRejectAssignment = () =>
  useAssignmentWrite<AssignmentDecisionVariables<RejectDiscountAssignmentBody>>((token, { assignmentId, body, idempotencyKey }) =>
    rejectDiscountAssignment(token, assignmentId, body, idempotencyKey),
  );

export const useRevokeAssignment = () =>
  useAssignmentWrite<AssignmentDecisionVariables<RevokeDiscountAssignmentBody>>((token, { assignmentId, body, idempotencyKey }) =>
    revokeDiscountAssignment(token, assignmentId, body, idempotencyKey),
  );
