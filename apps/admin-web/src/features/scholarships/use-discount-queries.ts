import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import type { ApproveDiscountChangeBody, RejectDiscountChangeBody } from "@confia/contracts/admin";
import {
  approveDiscountChange,
  deactivateDiscount,
  listDiscounts,
  reactivateDiscount,
  rejectDiscountChange,
  type Discount,
  type DiscountListParams,
} from "../../shared/api/scholarships-operations.ts";
import { LOOKUP_PAGE_SIZE } from "../../shared/organization-lookups/use-organization-queries.ts";
import { assignmentKeys, discountKeys } from "./scholarships-keys.ts";
import { writeDiscount, type DiscountSave } from "./discount-form.ts";

/** A listing of discounts, as the server pages, orders and filters it. The page on screen stays until the next arrives. */
export function useDiscountList(params: DiscountListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: discountKeys.list(params),
    queryFn: () => send((token) => listDiscounts(token, params)),
    placeholderData: keepPreviousData,
  });
}

/**
 * The discounts an assignment can name, for a select or a filter: one page of the largest size the API gives, in the order of the code. `agreementOnly`
 * leaves out the campaign ones (they apply by themselves and are never assigned) and the inactive ones (a new assignment needs an active agreement).
 */
export function useDiscountLookup(agreementOnly: boolean) {
  const list = useDiscountList({ page: 0, size: LOOKUP_PAGE_SIZE, sort: "code,asc", ...(agreementOnly ? { kind: "agreement" as const, active: true } : {}) });
  return { rows: list.data?.items ?? [], failed: list.isError, loading: list.isPending, retry: () => void list.refetch() };
}

/** The 409 codes that say what is on screen is no longer what the server holds (another version, a decision already taken, an approval that is now pending). */
const STALE_CODES: ReadonlySet<string> = new Set(["stale-version", "invalid-discount-change-state", "discount-pending-approval"]);

function refreshOnConflict(queryClient: QueryClient, error: unknown): void {
  if (!(error instanceof ApiFailure) || error.result.kind !== "problem" || !STALE_CODES.has(error.result.problem.code)) return;
  void queryClient.invalidateQueries({ queryKey: discountKeys.all });
}

/**
 * What every write of a discount has in common. It books nothing in the ledger (a discount only changes the charges to come), so nothing is optimistic
 * and no `Idempotency-Key` is sent, but it never repeats by itself. After a write the discounts are read again, and so are the assignments (they show
 * the code and the name of their discount).
 */
function useDiscountWrite<Variables>(operation: (token: string, variables: Variables) => ReturnType<typeof writeDiscount>) {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<Discount, Error, Variables>({
    mutationFn: (variables) => send((token) => operation(token, variables)),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: discountKeys.all }),
        queryClient.invalidateQueries({ queryKey: assignmentKeys.all }),
      ]);
    },
    onError: (error) => {
      refreshOnConflict(queryClient, error);
    },
  });
}

/** The creation or the edit of a discount. */
export const useDiscountSave = () => useDiscountWrite<DiscountSave>((token, save) => writeDiscount(token, save));

export interface DiscountToggle {
  readonly id: string;
  readonly active: boolean;
}

/** Deactivates or reactivates a discount: never a delete. A deactivated discount stops applying to the charges to come; the issued ones do not change. */
export const useDiscountToggle = () =>
  useDiscountWrite<DiscountToggle>((token, { id, active }) => (active ? reactivateDiscount(token, id) : deactivateDiscount(token, id)));

export interface DiscountDecisionVariables<Body> {
  readonly discountId: string;
  readonly body: Body;
  readonly idempotencyKey: string;
}

/**
 * The approval of what waits on a campaign discount (its creation or a proposal). It books nothing in the ledger, so nothing is optimistic; it is a permanent
 * decision of the person and carries the `Idempotency-Key` of its intent. When the server confirms, the discounts are read again, and so are the assignments
 * (they show the code and the name of their discount); when it says the discount is no longer as shown, the same read happens.
 */
export const useApproveDiscount = () =>
  useDiscountWrite<DiscountDecisionVariables<ApproveDiscountChangeBody>>((token, { discountId, body, idempotencyKey }) => approveDiscountChange(token, discountId, body, idempotencyKey));

/** The rejection of what waits on a campaign discount, with the reason (20 to 500 characters). */
export const useRejectDiscount = () =>
  useDiscountWrite<DiscountDecisionVariables<RejectDiscountChangeBody>>((token, { discountId, body, idempotencyKey }) => rejectDiscountChange(token, discountId, body, idempotencyKey));
