import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { PaymentConceptForm } from "./components/PaymentConceptForm.tsx";
import {
  PAYMENT_CONCEPT_CONTROL_IDS,
  initialPaymentConceptValues,
  saveOf,
  validatePaymentConcept,
  type PaymentConcept,
  type PaymentConceptField,
  type PaymentConceptSave,
} from "./payment-concept-form.ts";

export interface PaymentConceptFormContainerProps {
  /** Absent when the concept is new. */
  readonly row?: PaymentConcept;
  /** Rejects with the failure of the request; resolves when the concept is saved. */
  readonly save: (save: PaymentConceptSave) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a payment concept: validate before sending, send once, and turn a failure into what the form shows. */
export function PaymentConceptFormContainer({ row, save, onClose }: PaymentConceptFormContainerProps) {
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

  return (
    <PaymentConceptForm
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
