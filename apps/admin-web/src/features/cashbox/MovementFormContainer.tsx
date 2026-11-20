import { useRef } from "react";
import { useAccessForm, type FieldReason } from "../../shared/forms/use-access-form.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { cashFailureOf } from "./cashbox-failure.ts";
import { amountReasonOf, CASH_NOTE_MAX, CASH_CURRENCY, CASH_REASON_MAX, CASH_REASON_MIN, intentFor, noteReasonOf, type CashIntent } from "./cashbox-model.ts";
import type { CashMovementType, CashSession } from "./cashbox-types.ts";
import { MOVEMENT_CONTROL_IDS, MovementDialog, type MovementField } from "./components/MovementDialog.tsx";
import { useRecordCashMovement } from "./use-cashbox-queries.ts";

export interface MovementFormContainerProps {
  readonly session: CashSession;
  readonly type: CashMovementType;
  readonly onClose: () => void;
  /** The server confirmed the movement and the session is already read again. */
  readonly onRecorded: (type: CashMovementType) => void;
}

/**
 * The behavior of the form of a movement: validate before sending, send once, keep what was typed after a refusal (a withdrawal that the
 * expected cash cannot cover, a session closed meanwhile) and turn the failure into what the dialog shows. The `Idempotency-Key` belongs to the
 * request the person confirmed: it travels again unchanged if the answer never arrived, and a new request gets a new one.
 */
export function MovementFormContainer({ session, type, onClose, onRecorded }: MovementFormContainerProps) {
  const recording = useRecordCashMovement();
  const intent = useRef<CashIntent | undefined>(undefined);
  const adjustment = type === "adjustment";

  const form = useAccessForm<MovementField>({
    fields: fieldSpecs(MOVEMENT_CONTROL_IDS, { amount: "", notes: "" }, ["amount", "notes"]),
    validate: (values) => {
      const reasons: Partial<Record<MovementField, FieldReason>> = {};
      const amount = amountReasonOf(values.amount, true);
      if (amount !== undefined) reasons.amount = amount;
      // An adjustment needs its note (20 to 500 characters); the note of the others is optional.
      const notes = noteReasonOf(values.notes, adjustment ? { min: CASH_REASON_MIN, max: CASH_REASON_MAX, required: true } : { min: 1, max: CASH_NOTE_MAX, required: false });
      if (notes !== undefined) reasons.notes = notes;
      return reasons;
    },
    submit: async (values) => {
      const body = { type, amount: { amount: values.amount, currency: CASH_CURRENCY }, ...(values.notes === "" ? {} : { notes: values.notes }) };
      const current = intentFor(intent.current, JSON.stringify(["movement", session.id, body]));
      intent.current = current;
      try {
        await recording.mutateAsync({ sessionId: session.id, body, idempotencyKey: current.idempotencyKey });
        intent.current = undefined;
        onRecorded(type);
        return undefined;
      } catch (error) {
        const { failure, outcomeUnknown } = cashFailureOf(error);
        if (!outcomeUnknown) intent.current = undefined;
        return failure;
      }
    },
    waitingKey: "cashbox.movementForm.sending",
  });

  return (
    <MovementDialog
      type={type}
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
