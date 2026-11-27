import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { TextAreaField } from "../../../shared/resource-screen/components/fields.tsx";
import { AWARD_REASON_MAX, AWARD_REASON_MIN } from "../award-model.ts";
import { discountLabelOf, type DiscountAssignment } from "../discounts-types.ts";

export const ASSIGNMENT_REJECT_REASON_ID = "assignment-reject-reason";
export const ASSIGNMENT_REVOKE_REASON_ID = "assignment-revoke-reason";

/** What the person is deciding, restated from the assignment as the server holds it, so the decision is never blind. */
function Restatement({ assignment }: { readonly assignment: DiscountAssignment }) {
  const { t } = useAdminText();
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
      <dt className="text-text-secondary">{t("scholarships.assignments.detail.fields.student")}</dt>
      <dd>{assignment.studentCode ?? t("scholarships.assignments.detail.noValue")}</dd>
      <dt className="text-text-secondary">{t("scholarships.assignments.detail.fields.discount")}</dt>
      <dd>{discountLabelOf(assignment) ?? t("scholarships.assignments.detail.noValue")}</dd>
      <dt className="text-text-secondary">{t("scholarships.assignments.detail.fields.validity")}</dt>
      <dd>{t("scholarships.assignments.detail.validityValue", { from: formatIsoDate(assignment.validFrom), to: formatIsoDate(assignment.validTo) })}</dd>
    </dl>
  );
}

export interface ApproveAssignmentDialogProps {
  readonly assignment: DiscountAssignment;
  readonly form: ResourceFormView;
  readonly onClose: () => void;
}

/** The explicit approval of an assignment: it restates it and says what approving does (the charges issued from then on, not the ones already issued). */
export function ApproveAssignmentDialog({ assignment, form, onClose }: ApproveAssignmentDialogProps) {
  const { t } = useAdminText();
  return (
    <ResourceFormDialog
      title={t("scholarships.assignments.approve.title")}
      description={t("scholarships.assignments.approve.description")}
      submitLabel={t("scholarships.assignments.approve.submit")}
      submitVariant="success"
      form={form}
      onClose={onClose}
    >
      <Restatement assignment={assignment} />
    </ResourceFormDialog>
  );
}

export interface AssignmentReasonDialogProps {
  readonly assignment: DiscountAssignment;
  readonly reason: string;
  /** Already translated. */
  readonly reasonError?: string | undefined;
  readonly form: ResourceFormView;
  readonly onReasonChange: (reason: string) => void;
  readonly onReasonBlur: () => void;
  readonly onClose: () => void;
}

/** The explicit rejection of a pending assignment: it restates it and asks for the reason (20 to 500 characters), which is never copied to the audit log. */
export function RejectAssignmentDialog({ assignment, reason, reasonError, form, onReasonChange, onReasonBlur, onClose }: AssignmentReasonDialogProps) {
  const { t } = useAdminText();
  return (
    <ResourceFormDialog
      title={t("scholarships.assignments.reject.title")}
      description={t("scholarships.assignments.reject.description")}
      submitLabel={t("scholarships.assignments.reject.submit")}
      submitVariant="destructive"
      form={form}
      onClose={onClose}
    >
      <Restatement assignment={assignment} />
      <TextAreaField
        label={t("scholarships.assignments.reject.reason")}
        controlId={ASSIGNMENT_REJECT_REASON_ID}
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

/** The explicit revocation of an approved assignment: it says that only the charges to come are affected, and asks for the reason (20 to 500 characters). */
export function RevokeAssignmentDialog({ assignment, reason, reasonError, form, onReasonChange, onReasonBlur, onClose }: AssignmentReasonDialogProps) {
  const { t } = useAdminText();
  return (
    <ResourceFormDialog
      title={t("scholarships.assignments.revoke.title")}
      description={t("scholarships.assignments.revoke.description")}
      submitLabel={t("scholarships.assignments.revoke.submit")}
      submitVariant="destructive"
      form={form}
      onClose={onClose}
    >
      <Restatement assignment={assignment} />
      <TextAreaField
        label={t("scholarships.assignments.revoke.reason")}
        controlId={ASSIGNMENT_REVOKE_REASON_ID}
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
