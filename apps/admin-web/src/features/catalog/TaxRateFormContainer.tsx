import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { TaxRateForm } from "./components/TaxRateForm.tsx";
import {
  TAX_RATE_CONTROL_IDS,
  initialTaxRateValues,
  saveOf,
  validateTaxRate,
  type TaxRate,
  type TaxRateField,
  type TaxRateSave,
} from "./tax-rate-form.ts";

export interface TaxRateFormContainerProps {
  /** Absent when the rate is new. */
  readonly row?: TaxRate;
  /** Rejects with the failure of the request; resolves when the rate is saved. */
  readonly save: (save: TaxRateSave) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a tax rate: validate before sending, send once, and turn a failure into what the form shows. */
export function TaxRateFormContainer({ row, save, onClose }: TaxRateFormContainerProps) {
  const form = useAccessForm<TaxRateField>({
    fields: fieldSpecs(TAX_RATE_CONTROL_IDS, initialTaxRateValues(row), ["code", "name"]),
    validate: validateTaxRate(row),
    submit: async (values) => {
      try {
        await save(saveOf(row, values));
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "resource.saving",
  });

  return (
    <TaxRateForm
      mode={row === undefined ? "create" : "edit"}
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
