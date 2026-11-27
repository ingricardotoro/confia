import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { TextAreaField } from "../../../shared/resource-screen/components/fields.tsx";
import { AWARD_REASON_MAX, AWARD_REASON_MIN } from "../award-model.ts";
import { approvalStateOf } from "../discount-approval.ts";
import type { Discount } from "../discounts-types.ts";
import { DiscountTerms } from "./DiscountTerms.tsx";

export const DISCOUNT_REJECT_REASON_ID = "discount-reject-reason";

/**
 * What the person is deciding, restated from the discount as the server holds it, so the decision is never blind: a discount waiting for its creation shows
 * its terms; one with a proposal shows the terms in force beside the terms proposed.
 */
function Restatement({ discount }: { readonly discount: Discount }) {
  const { t } = useAdminText();
  const proposal = discount.pendingChange;
  if (proposal === undefined) {
    return (
      <section aria-labelledby="discount-terms-creation" className="flex flex-col gap-1">
        <h3 id="discount-terms-creation" className="text-sm font-semibold text-text-primary">
          {t("scholarships.discounts.creationTerms", { code: discount.code })}
        </h3>
        <DiscountTerms terms={discount} />
      </section>
    );
  }
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <section aria-labelledby="discount-terms-current" className="flex flex-col gap-1">
        <h3 id="discount-terms-current" className="text-sm font-semibold text-text-primary">
          {t("scholarships.discounts.currentTerms", { code: discount.code })}
        </h3>
        <DiscountTerms terms={discount} />
      </section>
      <section aria-labelledby="discount-terms-proposed" className="flex flex-col gap-1">
        <h3 id="discount-terms-proposed" className="text-sm font-semibold text-text-primary">
          {t("scholarships.discounts.proposedTerms")}
        </h3>
        <DiscountTerms terms={proposal} />
      </section>
    </div>
  );
}

export interface ApproveDiscountDialogProps {
  readonly discount: Discount;
  readonly form: ResourceFormView;
  readonly onClose: () => void;
}

/** The explicit approval of a campaign discount or of a proposal on it: it restates the terms and says what approving does. */
export function ApproveDiscountDialog({ discount, form, onClose }: ApproveDiscountDialogProps) {
  const { t } = useAdminText();
  const proposal = approvalStateOf(discount) === "proposal";
  return (
    <ResourceFormDialog
      title={t(proposal ? "scholarships.discounts.approve.proposalTitle" : "scholarships.discounts.approve.pendingTitle")}
      description={t(proposal ? "scholarships.discounts.approve.proposalDescription" : "scholarships.discounts.approve.pendingDescription")}
      submitLabel={t("scholarships.discounts.approve.submit")}
      submitVariant="success"
      form={form}
      onClose={onClose}
    >
      <Restatement discount={discount} />
    </ResourceFormDialog>
  );
}

export interface RejectDiscountDialogProps {
  readonly discount: Discount;
  readonly reason: string;
  /** Already translated. */
  readonly reasonError?: string | undefined;
  readonly form: ResourceFormView;
  readonly onReasonChange: (reason: string) => void;
  readonly onReasonBlur: () => void;
  readonly onClose: () => void;
}

/** The explicit rejection of a campaign discount or of a proposal on it: it restates the terms and asks for the reason (20 to 500 characters). */
export function RejectDiscountDialog({ discount, reason, reasonError, form, onReasonChange, onReasonBlur, onClose }: RejectDiscountDialogProps) {
  const { t } = useAdminText();
  const proposal = approvalStateOf(discount) === "proposal";
  return (
    <ResourceFormDialog
      title={t(proposal ? "scholarships.discounts.reject.proposalTitle" : "scholarships.discounts.reject.pendingTitle")}
      description={t(proposal ? "scholarships.discounts.reject.proposalDescription" : "scholarships.discounts.reject.pendingDescription")}
      submitLabel={t("scholarships.discounts.reject.submit")}
      submitVariant="destructive"
      form={form}
      onClose={onClose}
    >
      <Restatement discount={discount} />
      <TextAreaField
        label={t("scholarships.discounts.reject.reason")}
        controlId={DISCOUNT_REJECT_REASON_ID}
        required
        maxLength={AWARD_REASON_MAX}
        description={t("scholarships.reasonHelp", { min: AWARD_REASON_MIN, max: AWARD_REASON_MAX })}
        value={reason}
        error={reasonError}
        onChange={onReasonChange}
        onBlur={onReasonBlur}
      />
    </ResourceFormDialog>
  );
}
