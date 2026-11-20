import { useRef } from "react";
import { useAccessForm, type FieldReason } from "../../shared/forms/use-access-form.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { cashFailureOf } from "./cashbox-failure.ts";
import { amountReasonOf, CASH_CURRENCY, CASH_REASON_MAX, CASH_REASON_MIN, intentFor, noteReasonOf, type CashIntent } from "./cashbox-model.ts";
import type { CashSession } from "./cashbox-types.ts";
import { CLOSE_CONTROL_IDS, CloseSessionDialog, type CloseField } from "./components/CloseSessionDialog.tsx";
import { useCloseCashSession } from "./use-cashbox-queries.ts";

export interface CloseSessionContainerProps {
  readonly session: CashSession;
  readonly onClose: () => void;
  /** The server confirmed the close: the session as it answered it (the counted cash, the difference it computed, the status). */
  readonly onClosed: (closed: CashSession) => void;
}

/**
 * The behavior of the close: validate the counted cash and, when the person wrote one, the reason of the difference; send once; keep what was typed
 * after a refusal. Whether a difference needs its reason is the server's call, since only the server computes the difference: when it says so
 * (`cash-difference-reason-required`) the message appears under the reason field and the dialog stays open. The `Idempotency-Key` belongs to the
 * request the person confirmed and travels again unchanged if the answer never arrived.
 */
export function CloseSessionContainer({ session, onClose, onClosed }: CloseSessionContainerProps) {
  const closing = useCloseCashSession();
  const intent = useRef<CashIntent | undefined>(undefined);

  const form = useAccessForm<CloseField>({
    fields: fieldSpecs(CLOSE_CONTROL_IDS, { declaredAmount: "", differenceReason: "" }, ["declaredAmount", "differenceReason"]),
    validate: (values) => {
      const reasons: Partial<Record<CloseField, FieldReason>> = {};
      const declared = amountReasonOf(values.declaredAmount, false);
      if (declared !== undefined) reasons.declaredAmount = declared;
      const reason = noteReasonOf(values.differenceReason, { min: CASH_REASON_MIN, max: CASH_REASON_MAX, required: false });
      if (reason !== undefined) reasons.differenceReason = reason;
      return reasons;
    },
    submit: async (values) => {
      const body = {
        declaredAmount: { amount: values.declaredAmount, currency: CASH_CURRENCY },
        ...(values.differenceReason === "" ? {} : { differenceReason: values.differenceReason }),
      };
      const current = intentFor(intent.current, JSON.stringify(["close", session.id, body]));
      intent.current = current;
      try {
        const closed = await closing.mutateAsync({ sessionId: session.id, body, idempotencyKey: current.idempotencyKey });
        intent.current = undefined;
        onClosed(closed);
        return undefined;
      } catch (error) {
        const { failure, outcomeUnknown } = cashFailureOf(error);
        if (!outcomeUnknown) intent.current = undefined;
        return failure;
      }
    },
    waitingKey: "cashbox.close.sending",
  });

  return (
    <CloseSessionDialog
      expected={session.expectedAmount}
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
