import { useRef } from "react";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { cashFailureOf } from "./cashbox-failure.ts";
import { amountReasonOf, CASH_CURRENCY, intentFor, type CashIntent } from "./cashbox-model.ts";
import { OPENING_FLOAT_ID, OpenSessionForm } from "./components/OpenSessionForm.tsx";
import { useOpenCashSession } from "./use-cashbox-queries.ts";

export interface OpenSessionContainerProps {
  /** The server confirmed the opening and the session is already read again. */
  readonly onOpened: () => void;
}

/**
 * The behavior of the form that opens the session: validate the float before sending, send once, keep what was typed after a refusal, and
 * turn the failure into what the form shows. The `Idempotency-Key` is made once for the request the person confirms and sent again unchanged
 * if the answer never arrived; a float that changed, or an answer that settled the request, is a new intent with a new key.
 */
export function OpenSessionContainer({ onOpened }: OpenSessionContainerProps) {
  const opening = useOpenCashSession();
  const intent = useRef<CashIntent | undefined>(undefined);

  const form = useAccessForm<"openingFloat">({
    fields: fieldSpecs({ openingFloat: OPENING_FLOAT_ID }, { openingFloat: "" }, ["openingFloat"]),
    validate: (values) => {
      const reason = amountReasonOf(values.openingFloat, false);
      return reason === undefined ? {} : { openingFloat: reason };
    },
    submit: async (values) => {
      const body = { openingFloat: { amount: values.openingFloat, currency: CASH_CURRENCY } };
      const current = intentFor(intent.current, JSON.stringify(["open", body]));
      intent.current = current;
      try {
        await opening.mutateAsync({ body, idempotencyKey: current.idempotencyKey });
        intent.current = undefined;
        onOpened();
        return undefined;
      } catch (error) {
        const { failure, outcomeUnknown } = cashFailureOf(error);
        if (!outcomeUnknown) intent.current = undefined;
        return failure;
      }
    },
    waitingKey: "cashbox.open.opening",
  });

  return <OpenSessionForm form={form} openingFloat={form.values.openingFloat} error={form.fieldErrors.openingFloat} onChange={(value) => { form.onChange("openingFloat", value); }} onBlur={() => { form.onBlur("openingFloat"); }} />;
}
