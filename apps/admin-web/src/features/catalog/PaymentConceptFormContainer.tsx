import { useAdminText } from "../../i18n/use-admin-text.ts";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import type { Option } from "../../shared/resource-screen/components/fields.tsx";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { PaymentConceptForm } from "./components/PaymentConceptForm.tsx";
import {
  PAYMENT_CONCEPT_CONTROL_IDS,
  initialPaymentConceptValues,
  needsTaxRate,
  saveOf,
  validatePaymentConcept,
  type PaymentConcept,
  type PaymentConceptField,
  type PaymentConceptSave,
} from "./payment-concept-form.ts";
import { useActiveTaxRateLookup } from "./use-catalog-queries.ts";

export interface PaymentConceptFormContainerProps {
  /** Absent when the concept is new. */
  readonly row?: PaymentConcept;
  /** Rejects with the failure of the request; resolves when the concept is saved. */
  readonly save: (save: PaymentConceptSave) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a payment concept: validate before sending, send once, and turn a failure into what the form shows. */
export function PaymentConceptFormContainer({ row, save, onClose }: PaymentConceptFormContainerProps) {
  const { t } = useAdminText();
  const taxRates = useActiveTaxRateLookup();
  const form = useAccessForm<PaymentConceptField>({
    fields: fieldSpecs(PAYMENT_CONCEPT_CONTROL_IDS, initialPaymentConceptValues(row), ["code", "name"]),
    validate: validatePaymentConcept(row),
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

  // The active rates, read through the catalog's own client, and the one the concept already holds: it may have been
  // deactivated since, and the server lets a concept keep it, so the select must still show what the concept has.
  const held = row?.taxRateId;
  const taxRateOptions: Option[] = taxRates.rows.map((rate) => ({ value: rate.id, label: t("catalog.taxRates.optionLabel", { code: rate.code, name: rate.name }) }));
  if (held !== undefined && held !== null && !taxRates.rows.some((rate) => rate.id === held)) {
    taxRateOptions.push({
      value: held,
      label: t("catalog.paymentConcepts.taxRateInactive", { code: row?.taxRateCode ?? "", name: row?.taxRateName ?? "" }),
    });
  }

  return (
    <PaymentConceptForm
      mode={row === undefined ? "create" : "edit"}
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      taxRateOptions={taxRateOptions}
      taxRateLookupFailure={taxRates.failed ? { onRetry: taxRates.retry } : undefined}
      noActiveTaxRates={!taxRates.loading && !taxRates.failed && taxRates.rows.length === 0}
      onChange={(field, value) => {
        form.onChange(field, value);
        // A concept that is not taxable takes no rate: unchecking the box drops the one that was chosen.
        if (field === "isTaxable" && !needsTaxRate(value)) form.onChange("taxRateId", "");
      }}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
