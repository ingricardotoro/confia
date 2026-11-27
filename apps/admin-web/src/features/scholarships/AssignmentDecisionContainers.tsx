import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import {
  ApproveAssignmentDialog,
  ASSIGNMENT_REJECT_REASON_ID,
  ASSIGNMENT_REVOKE_REASON_ID,
  RejectAssignmentDialog,
  RevokeAssignmentDialog,
} from "./components/AssignmentDecisionDialogs.tsx";
import { awardReasonOf } from "./award-model.ts";
import type { DiscountAssignment } from "./discounts-types.ts";
import { assignmentFailureOf } from "./scholarships-failure.ts";
import { useApproveAssignment, useRejectAssignment, useRevokeAssignment } from "./use-assignment-queries.ts";
import { useDecisionIntent } from "./use-decision-intent.ts";

export interface AssignmentDecisionContainerProps {
  readonly assignment: DiscountAssignment;
  readonly onClose: () => void;
  /** The server confirmed and the assignment is already read again. */
  readonly onDecided: () => void;
}

/**
 * The behavior of the approval: the request is sent once, and every refusal of the server is told in the dialog (the person who requested the assignment
 * cannot approve it, an assignment already decided, a person without the permission, an inactive discount, an overlap with another approved
 * assignment). The `Idempotency-Key` belongs to the approval the person made and travels again unchanged if the answer never arrived.
 */
export function ApproveAssignmentContainer({ assignment, onClose, onDecided }: AssignmentDecisionContainerProps) {
  const approving = useApproveAssignment();
  const decide = useDecisionIntent(assignmentFailureOf);

  const form = useAccessForm<never>({
    fields: {},
    validate: () => ({}),
    submit: () =>
      decide(
        JSON.stringify(["approve", assignment.id]),
        (idempotencyKey) => approving.mutateAsync({ assignmentId: assignment.id, body: undefined, idempotencyKey }),
        onDecided,
      ),
    waitingKey: "scholarships.assignments.approve.sending",
  });

  return <ApproveAssignmentDialog assignment={assignment} form={form} onClose={onClose} />;
}

type ReasonMutation = ReturnType<typeof useRejectAssignment>;

/** The shared behavior of the two decisions that need a reason of 20 to 500 characters: validate it, send once, and keep the intent while the outcome is unknown. */
function useReasonDecision(assignment: DiscountAssignment, kind: "reject" | "revoke", mutation: ReasonMutation, controlId: string, onDecided: () => void) {
  const decide = useDecisionIntent(assignmentFailureOf);
  return useAccessForm<"reason">({
    fields: fieldSpecs({ reason: controlId }, { reason: "" }, ["reason"]),
    validate: (values) => {
      const reason = awardReasonOf(values.reason);
      return reason === undefined ? {} : { reason };
    },
    submit: (values) => {
      const body = { reason: values.reason.trim() };
      return decide(JSON.stringify([kind, assignment.id, body]), (idempotencyKey) => mutation.mutateAsync({ assignmentId: assignment.id, body, idempotencyKey }), onDecided);
    },
    waitingKey: kind === "reject" ? "scholarships.assignments.reject.sending" : "scholarships.assignments.revoke.sending",
  });
}

/** The behavior of the rejection: a reason of 20 to 500 characters is required, the request is sent once, and every refusal is told in the dialog. */
export function RejectAssignmentContainer({ assignment, onClose, onDecided }: AssignmentDecisionContainerProps) {
  const form = useReasonDecision(assignment, "reject", useRejectAssignment(), ASSIGNMENT_REJECT_REASON_ID, onDecided);
  return (
    <RejectAssignmentDialog
      assignment={assignment}
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
export function RevokeAssignmentContainer({ assignment, onClose, onDecided }: AssignmentDecisionContainerProps) {
  const form = useReasonDecision(assignment, "revoke", useRevokeAssignment(), ASSIGNMENT_REVOKE_REASON_ID, onDecided);
  return (
    <RevokeAssignmentDialog
      assignment={assignment}
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
