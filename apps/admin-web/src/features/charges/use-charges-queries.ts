import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import type { AddChargePlanItemRequest, CreateChargePlanRequest, UpdateChargePlanRequest } from "@confia/contracts/admin";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import {
  addChargePlanItem,
  createChargePlan,
  getChargePlan,
  listChargePlans,
  removeChargePlanItem,
  updateChargePlan,
  type ChargePlanListParams,
} from "../../shared/api/charges-operations.ts";
import type { ChargePlanItem } from "./charge-plan-item-form.ts";
import type { ChargePlan } from "./charge-plan-form.ts";
import { chargePlanKeys } from "./charges-keys.ts";

/** A listing of plans, as the server pages, orders and filters it: the rows come back as they were served. */
export function useChargePlanList(params: ChargePlanListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: chargePlanKeys.list(params),
    queryFn: () => send((token) => listChargePlans(token, params)),
  });
}

/** One plan with its items. */
export function useChargePlanDetail(id: string) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: chargePlanKeys.detail(id),
    queryFn: () => send((token) => getChargePlan(token, id)),
  });
}

/**
 * The 409 codes that say what is on screen is no longer what the server holds: a version that moved, a plan that is no
 * longer a draft (or no longer active), a concept that is already in the plan, or one that stopped being active.
 */
const CONFLICT_CODES: ReadonlySet<string> = new Set([
  "stale-version",
  "plan-not-editable",
  "plan-without-items",
  "duplicate-plan-item",
  "inactive-reference",
]);

/**
 * When a write is refused because the screen is out of date, every query of the plans is read again: the server is the
 * truth about what the plan holds, at which version and in which state.
 */
function refreshOnConflict(queryClient: QueryClient, error: unknown): void {
  if (!(error instanceof ApiFailure) || error.result.kind !== "problem" || !CONFLICT_CODES.has(error.result.problem.code)) return;
  void queryClient.invalidateQueries({ queryKey: chargePlanKeys.all });
}

/**
 * The creation of a plan. A plan books nothing in the ledger and holds no price, so it is not a financial write: nothing is
 * optimistic and no `Idempotency-Key` is sent, but it never repeats by itself. Every query of the plans is read again after it.
 */
export function useChargePlanCreate() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<ChargePlan, Error, CreateChargePlanRequest>({
    mutationFn: (body) => send((token) => createChargePlan(token, body)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: chargePlanKeys.all }),
  });
}

interface ChargePlanUpdate {
  readonly id: string;
  readonly body: UpdateChargePlanRequest;
}

/**
 * An edit, the activation or the archive of a plan, with the version it was read at. After it, the detail of the plan
 * and every listing are read again; so are they after a refusal that says the plan changed under the screen.
 */
export function useChargePlanUpdate() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<ChargePlan, Error, ChargePlanUpdate>({
    mutationFn: ({ id, body }) => send((token) => updateChargePlan(token, id, body)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: chargePlanKeys.all }),
    onError: (error) => {
      refreshOnConflict(queryClient, error);
    },
  });
}

interface ChargePlanItemAdd {
  readonly planId: string;
  readonly body: AddChargePlanItemRequest;
}

/**
 * A new item of a draft plan. Nothing is optimistic: the detail and the listings are read again, because the server
 * holds the items. A refused add (a repeated concept, a plan that is no longer a draft) changes nothing on the server and
 * reads the plan again, because the screen was out of date.
 */
export function useChargePlanItemAdd() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<ChargePlanItem, Error, ChargePlanItemAdd>({
    mutationFn: ({ planId, body }) => send((token) => addChargePlanItem(token, planId, body)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: chargePlanKeys.all }),
    onError: (error) => {
      refreshOnConflict(queryClient, error);
    },
  });
}

interface ChargePlanItemRemove {
  readonly planId: string;
  readonly itemId: string;
}

/** The removal of an item of a draft plan, with the same rereading as an add. */
export function useChargePlanItemRemove() {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<undefined, Error, ChargePlanItemRemove>({
    mutationFn: ({ planId, itemId }) => send((token) => removeChargePlanItem(token, planId, itemId)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: chargePlanKeys.all }),
    onError: (error) => {
      refreshOnConflict(queryClient, error);
    },
  });
}
