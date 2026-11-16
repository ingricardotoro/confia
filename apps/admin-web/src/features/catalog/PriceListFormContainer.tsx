import type { CreatePriceListRequest } from "@confia/contracts/admin";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import type { Option } from "../../shared/resource-screen/components/fields.tsx";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { PriceListForm } from "./components/PriceListForm.tsx";
import {
  PRICE_LIST_CONTROL_IDS,
  createBodyOf,
  initialPriceListValues,
  validatePriceList,
  type PriceListField,
} from "./price-list-form.ts";

export interface PriceListFormContainerProps {
  readonly yearOptions: readonly Option[];
  readonly modalityOptions: readonly Option[];
  readonly gradeOptions: readonly Option[];
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** Rejects with the failure of the request; resolves when the list is saved. */
  readonly save: (body: CreatePriceListRequest) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a new price list: validate before sending, send once, and turn a failure into what the form shows. */
export function PriceListFormContainer({ yearOptions, modalityOptions, gradeOptions, lookupFailure, save, onClose }: PriceListFormContainerProps) {
  const form = useAccessForm<PriceListField>({
    fields: fieldSpecs(PRICE_LIST_CONTROL_IDS, initialPriceListValues(), ["name"]),
    validate: validatePriceList,
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
    <PriceListForm
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      yearOptions={yearOptions}
      modalityOptions={modalityOptions}
      gradeOptions={gradeOptions}
      lookupFailure={lookupFailure}
      onChange={(field, value) => {
        form.onChange(field, value);
        // The reference chosen for one scope means nothing for another.
        if (field === "scope") form.onChange("scopeRefId", "");
      }}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
