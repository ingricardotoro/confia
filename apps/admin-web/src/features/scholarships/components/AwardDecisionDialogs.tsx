import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { TextAreaField } from "../../../shared/resource-screen/components/fields.tsx";
import { AWARD_REASON_MAX, AWARD_REASON_MIN } from "../award-model.ts";
import { scholarshipLabelOf, type Award } from "../scholarships-types.ts";

export const REJECT_REASON_ID = "award-reject-reason";
export const REVOKE_REASON_ID = "award-revoke-reason";

/** What the person is deciding, restated from the award as the server holds it, so the decision is never blind. */
function Restatement({ award }: { readonly award: Award }) {
  const { t } = useAdminText();
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
      <dt className="text-text-secondary">{t("scholarships.detail.fields.student")}</dt>
      <dd>{award.studentCode ?? t("scholarships.detail.noValue")}</dd>
      <dt className="text-text-secondary">{t("scholarships.detail.fields.scholarship")}</dt>
      <dd>{scholarshipLabelOf(award) ?? t("scholarships.detail.noValue")}</dd>
      <dt className="text-text-secondary">{t("scholarships.detail.fields.validity")}</dt>
      <dd>{t("scholarships.detail.validityValue", { from: formatIsoDate(award.validFrom), to: formatIsoDate(award.validTo) })}</dd>
    </dl>
  );
}

export interface ApproveAwardDialogProps {
  readonly award: Award;
  readonly form: ResourceFormView;
  readonly onClose: () => void;
}

/** The explicit approval of an award: it restates the award and says what approving does (the charges issued from then on, not the ones already issued). */
export function ApproveAwardDialog({ award, form, onClose }: ApproveAwardDialogProps) {
  const { t } = useAdminText();
  return (
    <ResourceFormDialog title={t("scholarships.approve.title")} description={t("scholarships.approve.description")} submitLabel={t("scholarships.approve.submit")} submitVariant="success" form={form} onClose={onClose}>
      <Restatement award={award} />
    </ResourceFormDialog>
  );
}

export interface ReasonDialogProps {
  readonly award: Award;
  readonly reason: string;
  /** Already translated. */
  readonly reasonError?: string | undefined;
  readonly form: ResourceFormView;
  readonly onReasonChange: (reason: string) => void;
  readonly onReasonBlur: () => void;
  readonly onClose: () => void;
}

/** The explicit rejection of a pending award: it restates the award and asks for the reason (20 to 500 characters), which is never copied to the audit log. */
export function RejectAwardDialog({ award, reason, reasonError, form, onReasonChange, onReasonBlur, onClose }: ReasonDialogProps) {
  const { t } = useAdminText();
  return (
    <ResourceFormDialog title={t("scholarships.reject.title")} description={t("scholarships.reject.description")} submitLabel={t("scholarships.reject.submit")} submitVariant="destructive" form={form} onClose={onClose}>
      <Restatement award={award} />
      <TextAreaField
        label={t("scholarships.reject.reason")}
        controlId={REJECT_REASON_ID}
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

/** The explicit revocation of an approved award: it says that only the charges to come are affected, and asks for the reason (20 to 500 characters). */
export function RevokeAwardDialog({ award, reason, reasonError, form, onReasonChange, onReasonBlur, onClose }: ReasonDialogProps) {
  const { t } = useAdminText();
  return (
    <ResourceFormDialog title={t("scholarships.revoke.title")} description={t("scholarships.revoke.description")} submitLabel={t("scholarships.revoke.submit")} submitVariant="destructive" form={form} onClose={onClose}>
      <Restatement award={award} />
      <TextAreaField
        label={t("scholarships.revoke.reason")}
        controlId={REVOKE_REASON_ID}
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
