import type { AddChargePlanItemRequest } from "@confia/contracts/admin";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import type { Option } from "../../shared/resource-screen/components/fields.tsx";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import {
  CHARGE_PLAN_ITEM_CONTROL_IDS,
  addBodyOf,
  initialChargePlanItemValues,
  validateChargePlanItem,
  type ChargePlanItemField,
} from "./charge-plan-item-form.ts";
import { ChargePlanItemForm } from "./components/ChargePlanItemForm.tsx";

export interface ChargePlanItemFormContainerProps {
  readonly conceptOptions: readonly Option[];
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly noConcepts: boolean;
  /** Rejects with the failure of the request; resolves when the item is saved. */
  readonly save: (body: AddChargePlanItemRequest) => Promise<void>;
  readonly onClose: () => void;
}

/**
 * The behavior of the form of a new item: validate before sending, send once, keep what was typed after a refusal (a
 * repeated concept, a plan that is no longer a draft) and turn the failure into what the form shows.
 */
export function ChargePlanItemFormContainer({ conceptOptions, lookupFailure, noConcepts, save, onClose }: ChargePlanItemFormContainerProps) {
  const form = useAccessForm<ChargePlanItemField>({
    fields: fieldSpecs(CHARGE_PLAN_ITEM_CONTROL_IDS, initialChargePlanItemValues(), ["periodsCount"]),
    validate: validateChargePlanItem,
    submit: async (values) => {
      try {
        await save(addBodyOf(values));
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "resource.saving",
  });

  return (
    <ChargePlanItemForm
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      conceptOptions={conceptOptions}
      lookupFailure={lookupFailure}
      noConcepts={noConcepts}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
