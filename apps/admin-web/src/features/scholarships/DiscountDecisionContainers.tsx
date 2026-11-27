import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { ApproveDiscountDialog, DISCOUNT_REJECT_REASON_ID, RejectDiscountDialog } from "./components/DiscountDecisionDialogs.tsx";
import { awardReasonOf } from "./award-model.ts";
import type { Discount } from "./discounts-types.ts";
import { discountFailureOf } from "./scholarships-failure.ts";
import { useDecisionIntent } from "./use-decision-intent.ts";
import { useApproveDiscount, useRejectDiscount } from "./use-discount-queries.ts";

export interface DiscountDecisionContainerProps {
  /** The discount as the screen read it: the decision names its version, so it is never taken on terms that changed. */
  readonly discount: Discount;
  readonly onClose: () => void;
  /** The server confirmed and the discounts are already read again. */
  readonly onDecided: () => void;
}

/**
 * The behavior of the approval of what waits on a campaign discount (its creation or a proposal on it): the request is sent once, with the version the person
 * saw, and every refusal of the server is told in the dialog (the person who created it or proposed the change cannot approve it, a discount with nothing
 * pending, a version that changed, a person without the permission). The `Idempotency-Key` belongs to the approval the person made and travels again
 * unchanged if the answer never arrived (the same helper as the assignments).
 */
export function ApproveDiscountContainer({ discount, onClose, onDecided }: DiscountDecisionContainerProps) {
  const approving = useApproveDiscount();
  const decide = useDecisionIntent(discountFailureOf);

  const form = useAccessForm<never>({
    fields: {},
    validate: () => ({}),
    submit: () => {
      const body = { version: discount.version };
      return decide(JSON.stringify(["approve", discount.id, body]), (idempotencyKey) => approving.mutateAsync({ discountId: discount.id, body, idempotencyKey }), onDecided);
    },
    waitingKey: "scholarships.discounts.approve.sending",
  });

  return <ApproveDiscountDialog discount={discount} form={form} onClose={onClose} />;
}

/** The behavior of the rejection: a reason of 20 to 500 characters is required, the request is sent once with the version the person saw, and every refusal is told in the dialog. */
export function RejectDiscountContainer({ discount, onClose, onDecided }: DiscountDecisionContainerProps) {
  const rejecting = useRejectDiscount();
  const decide = useDecisionIntent(discountFailureOf);

  const form = useAccessForm<"reason">({
    fields: fieldSpecs({ reason: DISCOUNT_REJECT_REASON_ID }, { reason: "" }, ["reason"]),
    validate: (values) => {
      const reason = awardReasonOf(values.reason);
      return reason === undefined ? {} : { reason };
    },
    submit: (values) => {
      const body = { version: discount.version, reason: values.reason.trim() };
      return decide(JSON.stringify(["reject", discount.id, body]), (idempotencyKey) => rejecting.mutateAsync({ discountId: discount.id, body, idempotencyKey }), onDecided);
    },
    waitingKey: "scholarships.discounts.reject.sending",
  });

  return (
    <RejectDiscountDialog
      discount={discount}
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
