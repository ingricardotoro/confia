import type { CreateChargePlanRequest } from "@confia/contracts/admin";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import type { Option } from "../../shared/resource-screen/components/fields.tsx";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import {
  CHARGE_PLAN_CONTROL_IDS,
  createBodyOf,
  initialChargePlanValues,
  validateChargePlan,
  type ChargePlanField,
} from "./charge-plan-form.ts";
import { ChargePlanForm } from "./components/ChargePlanForm.tsx";

export interface ChargePlanFormContainerProps {
  readonly yearOptions: readonly Option[];
  readonly modalityOptions: readonly Option[];
  readonly gradeOptions: readonly Option[];
  readonly sectionOptions: readonly Option[];
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** Rejects with the failure of the request; resolves when the plan is saved. */
  readonly save: (body: CreateChargePlanRequest) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a new plan: validate before sending, send once, and turn a failure into what the form shows. */
export function ChargePlanFormContainer({ yearOptions, modalityOptions, gradeOptions, sectionOptions, lookupFailure, save, onClose }: ChargePlanFormContainerProps) {
  const form = useAccessForm<ChargePlanField>({
    fields: fieldSpecs(CHARGE_PLAN_CONTROL_IDS, initialChargePlanValues(), ["name", "dueDayOfMonth"]),
    validate: validateChargePlan,
    submit: async (values) => {
      try {
        await save(createBodyOf(values));
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "resource.saving",
  });

  return (
    <ChargePlanForm
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      yearOptions={yearOptions}
      modalityOptions={modalityOptions}
      gradeOptions={gradeOptions}
      sectionOptions={sectionOptions}
      lookupFailure={lookupFailure}
      onChange={(field, value) => {
        form.onChange(field, value);
        // The reference chosen for one scope means nothing for another.
        if (field === "targetScope") form.onChange("targetRefId", "");
      }}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
