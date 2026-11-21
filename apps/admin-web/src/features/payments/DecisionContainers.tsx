import { useRef } from "react";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { intentFor, type IdempotentIntent } from "../../shared/forms/idempotent-intent.ts";
import { ConfirmPaymentDialog, RejectPaymentDialog, REJECT_REASON_ID } from "./components/DecisionDialogs.tsx";
import { paymentFailureOf } from "./payments-failure.ts";
import { rejectionReasonOf } from "./payments-model.ts";
import type { Payment } from "./payments-types.ts";
import { useConfirmPayment, useRejectPayment } from "./use-payment-queries.ts";

export interface ConfirmPaymentContainerProps {
  /** A declared transfer. */
  readonly payment: Payment;
  readonly onClose: () => void;
  /** The server confirmed and the payment is already read again. */
  readonly onConfirmed: () => void;
}

/**
 * The behavior of the confirmation: the request is sent once, and every refusal of the server is told in the dialog (the person who registered
 * the payment cannot confirm it, a payment already decided, a person without the permission, the accounts of the ledger incomplete). The
 * `Idempotency-Key` belongs to the confirmation the person made and travels again unchanged if the answer never arrived.
 */
export function ConfirmPaymentContainer({ payment, onClose, onConfirmed }: ConfirmPaymentContainerProps) {
  const confirming = useConfirmPayment();
  const intent = useRef<IdempotentIntent | undefined>(undefined);

  const form = useAccessForm<never>({
    fields: {},
    validate: () => ({}),
    submit: async () => {
      const current = intentFor(intent.current, JSON.stringify(["confirm", payment.id]));
      intent.current = current;
      try {
        await confirming.mutateAsync({ paymentId: payment.id, body: undefined, idempotencyKey: current.idempotencyKey });
        intent.current = undefined;
        onConfirmed();
        return undefined;
      } catch (error) {
        const { failure, outcomeUnknown } = paymentFailureOf(error);
        if (!outcomeUnknown) intent.current = undefined;
        return failure;
      }
    },
    waitingKey: "payments.confirm.sending",
  });

  return <ConfirmPaymentDialog payment={payment} form={form} onClose={onClose} />;
}

export interface RejectPaymentContainerProps {
  readonly payment: Payment;
  readonly onClose: () => void;
  /** The server rejected and the payment is already read again. */
  readonly onRejected: () => void;
}

/** The behavior of the rejection: a reason of 20 to 500 characters is required, the request is sent once, and every refusal is told in the dialog. */
export function RejectPaymentContainer({ payment, onClose, onRejected }: RejectPaymentContainerProps) {
  const rejecting = useRejectPayment();
  const intent = useRef<IdempotentIntent | undefined>(undefined);

  const form = useAccessForm<"reason">({
    fields: fieldSpecs({ reason: REJECT_REASON_ID }, { reason: "" }, ["reason"]),
    validate: (values) => {
      const reason = rejectionReasonOf(values.reason);
      return reason === undefined ? {} : { reason };
    },
    submit: async (values) => {
      const body = { reason: values.reason.trim() };
      const current = intentFor(intent.current, JSON.stringify(["reject", payment.id, body]));
      intent.current = current;
      try {
        await rejecting.mutateAsync({ paymentId: payment.id, body, idempotencyKey: current.idempotencyKey });
        intent.current = undefined;
        onRejected();
        return undefined;
      } catch (error) {
        const { failure, outcomeUnknown } = paymentFailureOf(error);
        if (!outcomeUnknown) intent.current = undefined;
        return failure;
      }
    },
    waitingKey: "payments.reject.sending",
  });

  return (
    <RejectPaymentDialog
      payment={payment}
      reason={form.values.reason}
      reasonError={form.fieldErrors.reason}
      form={form}
      onReasonChange={(reason) => {
        form.onChange("reason", reason);
      }}
      onReasonBlur={() => {
        form.onBlur("reason");
      }}
      onClose={onClose}
    />
  );
}
