import type { UpdateChargePlanRequest } from "@confia/contracts/admin";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import {
  CHARGE_PLAN_EDIT_CONTROL_IDS,
  editBodyOf,
  editValuesOf,
  validateChargePlanEdit,
  type ChargePlan,
  type ChargePlanEditField,
} from "./charge-plan-form.ts";
import { ChargePlanEditForm } from "./components/ChargePlanEditForm.tsx";

export interface ChargePlanEditContainerProps {
  readonly plan: ChargePlan;
  /** Rejects with the failure of the request; resolves when the plan is saved. */
  readonly save: (body: UpdateChargePlanRequest) => Promise<void>;
  readonly onClose: () => void;
}

/**
 * The behavior of the edit of a plan: it starts from the plan as it was read (and with its version), validates before
 * sending, sends once, and keeps what was typed after a refusal (a stale version, a plan that is no longer editable). An
 * active plan is frozen: the form shows its frequency and due day as they are and cannot change them.
 */
export function ChargePlanEditContainer({ plan, save, onClose }: ChargePlanEditContainerProps) {
  const initial = editValuesOf(plan);
  const form = useAccessForm<ChargePlanEditField>({
    fields: fieldSpecs(CHARGE_PLAN_EDIT_CONTROL_IDS, initial, ["name", "dueDayOfMonth"]),
    validate: validateChargePlanEdit,
    submit: async (values) => {
      try {
        await save(editBodyOf(plan, values));
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "resource.saving",
  });

  return (
    <ChargePlanEditForm
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      frozen={plan.status !== "DRAFT"}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
