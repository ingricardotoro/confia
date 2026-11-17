import type { AddTaxRatePeriodRequest } from "@confia/contracts/admin";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { TaxRatePeriodForm } from "./components/TaxRatePeriodForm.tsx";
import {
  TAX_RATE_PERIOD_CONTROL_IDS,
  addBodyOf,
  initialTaxRatePeriodValues,
  validateTaxRatePeriod,
  type TaxRatePeriodField,
} from "./tax-rate-period-form.ts";

export interface TaxRatePeriodFormContainerProps {
  /** Rejects with the failure of the request; resolves when the validity is saved. */
  readonly save: (body: AddTaxRatePeriodRequest) => Promise<void>;
  readonly onClose: () => void;
}

/**
 * The behavior of the form of a new validity: validate before sending, send once, keep what was typed after a refusal
 * (an overlap, an inactive rate, a stale version) and turn the failure into what the form shows.
 */
export function TaxRatePeriodFormContainer({ save, onClose }: TaxRatePeriodFormContainerProps) {
  const form = useAccessForm<TaxRatePeriodField>({
    fields: fieldSpecs(TAX_RATE_PERIOD_CONTROL_IDS, initialTaxRatePeriodValues(), ["ratePercent"]),
    validate: validateTaxRatePeriod,
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
    <TaxRatePeriodForm
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
