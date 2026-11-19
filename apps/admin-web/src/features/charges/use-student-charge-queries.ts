import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { listStudentCharges, voidCharge, type ChargeVoid, type StudentChargesParams } from "../../shared/api/charges-operations.ts";
import { statementKeys, studentChargeKeys } from "./charges-keys.ts";
import { strippedReasonOf, type VoidIntent } from "./void-model.ts";

/**
 * One page of the charges of a student, as the server pages, filters and orders it. A read, never optimistic and never kept apart
 * from the cache. Nothing is read until a student is chosen; changing the page keeps the page on screen until the next arrives,
 * changing the student never shows the previous student's charges.
 */
export function useStudentChargeList(studentId: string | undefined, params: StudentChargesParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: studentChargeKeys.list(studentId ?? "", params),
    queryFn: () => send((token) => listStudentCharges(token, studentId ?? "", params)),
    enabled: studentId !== undefined,
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[2] === studentId ? previous : undefined),
  });
}

/** The answers after which the list on screen is stale: the charge is already voided, or it no longer exists. */
const STALE_LIST_CODES: ReadonlySet<string> = new Set(["charge-already-voided", "resource-not-found"]);

/**
 * The void of a charge. It moves money (the server posts the mirror entry in the ledger), so nothing is optimistic and the
 * \`Idempotency-Key\` of the intent travels with every try of it. When the server confirms, everything derived from the ledger is read
 * again: every list of charges (the charge is now voided) and every statement of account (its reversal line and the running
 * balances). The balance and the dashboard indicators have no query of their own yet; when they do, their prefixes are
 * invalidated here too.
 */
export function useVoidCharge() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<ChargeVoid, Error, VoidIntent>({
    mutationFn: (intent) => send((token) => voidCharge(token, intent.chargeId, { reason: strippedReasonOf(intent.reason) }, intent.idempotencyKey)),
    onSuccess: async () => {
      await Promise.all([queryClient.invalidateQueries({ queryKey: studentChargeKeys.all }), queryClient.invalidateQueries({ queryKey: statementKeys.all })]);
    },
    onError: async (error) => {
      // The server says the charge is already voided or gone: the row on screen is out of date.
      if (error instanceof ApiFailure && error.result.kind === "problem" && STALE_LIST_CODES.has(error.result.problem.code)) {
        await queryClient.invalidateQueries({ queryKey: studentChargeKeys.all });
      }
    },
  });
}
