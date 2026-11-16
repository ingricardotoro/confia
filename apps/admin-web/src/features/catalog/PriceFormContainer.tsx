import type { AddPriceRequest } from "@confia/contracts/admin";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import type { Option } from "../../shared/resource-screen/components/fields.tsx";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { PriceForm } from "./components/PriceForm.tsx";
import { PRICE_CONTROL_IDS, addBodyOf, initialPriceValues, validatePrice, type PriceField } from "./price-form.ts";

export interface PriceFormContainerProps {
  readonly conceptOptions: readonly Option[];
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly noActiveConcepts: boolean;
  /** Rejects with the failure of the request; resolves when the price is saved. */
  readonly save: (body: AddPriceRequest) => Promise<void>;
  readonly onClose: () => void;
}

/**
 * The behavior of the form of a new price or validity: validate before sending, send once, keep what was typed after a
 * refusal (an overlap, an archived list, a stale version) and turn the failure into what the form shows.
 */
export function PriceFormContainer({ conceptOptions, lookupFailure, noActiveConcepts, save, onClose }: PriceFormContainerProps) {
  const form = useAccessForm<PriceField>({
    fields: fieldSpecs(PRICE_CONTROL_IDS, initialPriceValues(), ["amount"]),
    validate: validatePrice,
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
    <PriceForm
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      conceptOptions={conceptOptions}
      lookupFailure={lookupFailure}
      noActiveConcepts={noActiveConcepts}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
