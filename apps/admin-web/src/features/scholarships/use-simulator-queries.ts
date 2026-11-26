import { useMutation, useQuery } from "@tanstack/react-query";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import { listAccrualPeriods, listChargePlans, simulateCharges, type ChargeSimulation } from "../../shared/api/charges-operations.ts";
import { LOOKUP_PAGE_SIZE } from "../../shared/organization-lookups/use-organization-queries.ts";
import { simulationKeys } from "./scholarships-keys.ts";

/** The plans a simulation can name: the active ones, one page of the largest size the API gives, in the order of the name. */
const ACTIVE_PLANS = { page: 0, size: LOOKUP_PAGE_SIZE, sort: "name,asc", status: "ACTIVE" } as const;

export function useActivePlans() {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: simulationKeys.plans(ACTIVE_PLANS),
    queryFn: () => send((token) => listChargePlans(token, ACTIVE_PLANS)),
  });
}

/** The periods a plan accrues, as the server derives them. Nothing is read until a plan is chosen. */
export function useSimulatorPeriods(planId: string | undefined) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: simulationKeys.periods(planId ?? ""),
    queryFn: () => send((token) => listAccrualPeriods(token, planId ?? "")),
    enabled: planId !== undefined,
  });
}

export interface SimulationSelection {
  readonly planId: string;
  readonly studentId: string;
  /** `yyyy-MM-dd`: the first day of the period. */
  readonly period: string;
}

/**
 * The simulation of one period for one student. It is a calculation the server does and writes nothing, so it is not cached: every press asks again,
 * and its answer lives only as long as the choice it was made for. The server audits each consultation (they are data of a minor), which is why it is
 * asked for on demand and never again by itself. Repeating it is safe and sends no `Idempotency-Key`.
 */
export function useChargeSimulation() {
  const send = useAuthorizedSend();
  return useMutation<ChargeSimulation, Error, SimulationSelection>({
    mutationFn: ({ planId, studentId, period }) => send((token) => simulateCharges(token, planId, { studentId, period })),
  });
}
