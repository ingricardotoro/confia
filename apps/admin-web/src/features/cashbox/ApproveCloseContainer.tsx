import { useRef } from "react";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { cashFailureOf } from "./cashbox-failure.ts";
import { CASH_NOTE_MAX, intentFor, noteReasonOf, type CashIntent } from "./cashbox-model.ts";
import type { CashSession } from "./cashbox-types.ts";
import { APPROVE_NOTE_ID, ApproveCloseDialog } from "./components/ApproveCloseDialog.tsx";
import { useApproveCashSessionClose } from "./use-cashbox-queries.ts";

export interface ApproveCloseContainerProps {
  /** A session that awaits its approval, so it has a close. */
  readonly session: CashSession;
  readonly onClose: () => void;
  /** The server confirmed the approval and the session is already read again. */
  readonly onApproved: () => void;
}

/**
 * The behavior of the approval: a note is required, the request is sent once, and every refusal of the server is told in the dialog (the cashier
 * who closed the session cannot approve it, a session already approved, a person without the permission). The `Idempotency-Key` belongs to the
 * approval the person confirmed and travels again unchanged if the answer never arrived.
 */
export function ApproveCloseContainer({ session, onClose, onApproved }: ApproveCloseContainerProps) {
  const approving = useApproveCashSessionClose();
  const intent = useRef<CashIntent | undefined>(undefined);
  const { close } = session;

  const form = useAccessForm<"note">({
    fields: fieldSpecs({ note: APPROVE_NOTE_ID }, { note: "" }, ["note"]),
    validate: (values) => {
      const reason = noteReasonOf(values.note, { min: 1, max: CASH_NOTE_MAX, required: true });
      return reason === undefined ? {} : { note: reason };
    },
    submit: async (values) => {
      const body = { note: values.note };
      const current = intentFor(intent.current, JSON.stringify(["approve", session.id, body]));
      intent.current = current;
      try {
        await approving.mutateAsync({ sessionId: session.id, body, idempotencyKey: current.idempotencyKey });
        intent.current = undefined;
        onApproved();
        return undefined;
      } catch (error) {
        const { failure, outcomeUnknown } = cashFailureOf(error);
        if (!outcomeUnknown) intent.current = undefined;
        return failure;
      }
    },
    waitingKey: "cashbox.approve.sending",
  });

  if (close === undefined) return null;
  return (
    <ApproveCloseDialog
      close={close}
      note={form.values.note}
      noteError={form.fieldErrors.note}
      form={form}
      onNoteChange={(note) => {
        form.onChange("note", note);
      }}
      onNoteBlur={() => {
        form.onBlur("note");
      }}
      onClose={onClose}
    />
  );
}
