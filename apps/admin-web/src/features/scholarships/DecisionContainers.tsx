import { useRef } from "react";
import { intentFor, type IdempotentIntent } from "../../shared/forms/idempotent-intent.ts";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { ApproveAwardDialog, RejectAwardDialog, RevokeAwardDialog, REJECT_REASON_ID, REVOKE_REASON_ID } from "./components/AwardDecisionDialogs.tsx";
import { awardReasonOf } from "./award-model.ts";
import { awardFailureOf } from "./scholarships-failure.ts";
import type { Award } from "./scholarships-types.ts";
import { useApproveAward, useRejectAward, useRevokeAward } from "./use-award-queries.ts";

export interface DecisionContainerProps {
  readonly award: Award;
  readonly onClose: () => void;
  /** The server confirmed and the award is already read again. */
  readonly onDecided: () => void;
}

/**
 * The behavior of the approval: the request is sent once, and every refusal of the server is told in the dialog (the person who requested the award
 * cannot approve it, an award already decided, a person without the permission, an inactive scholarship, an overlap with another approved award). The
 * `Idempotency-Key` belongs to the approval the person made and travels again unchanged if the answer never arrived.
 */
export function ApproveAwardContainer({ award, onClose, onDecided }: DecisionContainerProps) {
  const approving = useApproveAward();
  const intent = useRef<IdempotentIntent | undefined>(undefined);

  const form = useAccessForm<never>({
    fields: {},
    validate: () => ({}),
    submit: async () => {
      const current = intentFor(intent.current, JSON.stringify(["approve", award.id]));
      intent.current = current;
      try {
        await approving.mutateAsync({ awardId: award.id, body: undefined, idempotencyKey: current.idempotencyKey });
        intent.current = undefined;
        onDecided();
        return undefined;
      } catch (error) {
        const { failure, outcomeUnknown } = awardFailureOf(error);
        if (!outcomeUnknown) intent.current = undefined;
        return failure;
      }
    },
    waitingKey: "scholarships.approve.sending",
  });

  return <ApproveAwardDialog award={award} form={form} onClose={onClose} />;
}

type ReasonMutation = ReturnType<typeof useRejectAward>;

/** The shared behavior of the two decisions that need a reason of 20 to 500 characters: validate it, send once, and keep the intent while the outcome is unknown. */
function useReasonDecision(award: Award, kind: "reject" | "revoke", mutation: ReasonMutation, controlId: string, onDecided: () => void) {
  const intent = useRef<IdempotentIntent | undefined>(undefined);
  return useAccessForm<"reason">({
    fields: fieldSpecs({ reason: controlId }, { reason: "" }, ["reason"]),
    validate: (values) => {
      const reason = awardReasonOf(values.reason);
      return reason === undefined ? {} : { reason };
    },
    submit: async (values) => {
      const body = { reason: values.reason.trim() };
      const current = intentFor(intent.current, JSON.stringify([kind, award.id, body]));
      intent.current = current;
      try {
        await mutation.mutateAsync({ awardId: award.id, body, idempotencyKey: current.idempotencyKey });
        intent.current = undefined;
        onDecided();
        return undefined;
      } catch (error) {
        const { failure, outcomeUnknown } = awardFailureOf(error);
        if (!outcomeUnknown) intent.current = undefined;
        return failure;
      }
    },
    waitingKey: kind === "reject" ? "scholarships.reject.sending" : "scholarships.revoke.sending",
  });
}

/** The behavior of the rejection: a reason of 20 to 500 characters is required, the request is sent once, and every refusal is told in the dialog. */
export function RejectAwardContainer({ award, onClose, onDecided }: DecisionContainerProps) {
  const form = useReasonDecision(award, "reject", useRejectAward(), REJECT_REASON_ID, onDecided);
  return (
    <RejectAwardDialog
      award={award}
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

/** The behavior of the revocation: a reason of 20 to 500 characters is required, the request is sent once, and every refusal is told in the dialog. */
export function RevokeAwardContainer({ award, onClose, onDecided }: DecisionContainerProps) {
  const form = useReasonDecision(award, "revoke", useRevokeAward(), REVOKE_REASON_ID, onDecided);
  return (
    <RevokeAwardDialog
      award={award}
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
