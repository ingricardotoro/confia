import { MoneyAmount } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { TextAreaField } from "../../../shared/resource-screen/components/fields.tsx";
import { REJECTION_REASON_MAX, REJECTION_REASON_MIN } from "../payments-model.ts";
import type { Payment } from "../payments-types.ts";

export const REJECT_REASON_ID = "payments-reject-reason";

/** What the person is deciding, restated from the payment as the server holds it, so the decision is never blind. */
function Restatement({ payment }: { readonly payment: Payment }) {
  const { t } = useAdminText();
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
      <dt className="text-text-secondary">{t("payments.detail.fields.student")}</dt>
      <dd>{payment.studentCode ?? t("payments.detail.noValue")}</dd>
      <dt className="text-text-secondary">{t("payments.detail.fields.amount")}</dt>
      <dd className="text-right">
        <MoneyAmount value={payment.amount} size="sm" emphasis="strong" />
      </dd>
      <dt className="text-text-secondary">{t("payments.detail.fields.receivedOn")}</dt>
      <dd>{formatIsoDate(payment.receivedOn)}</dd>
      <dt className="text-text-secondary">{t("payments.detail.fields.bankName")}</dt>
      <dd>{payment.bankName ?? t("payments.detail.noValue")}</dd>
      <dt className="text-text-secondary">{t("payments.detail.fields.reference")}</dt>
      <dd>{payment.reference ?? t("payments.detail.noValue")}</dd>
    </dl>
  );
}

export interface ConfirmPaymentDialogProps {
  readonly payment: Payment;
  readonly form: ResourceFormView;
  readonly onClose: () => void;
}

/** The explicit confirmation of a declared transfer: it restates the payment and says what confirming does. Self-approval is the server refusal. */
export function ConfirmPaymentDialog({ payment, form, onClose }: ConfirmPaymentDialogProps) {
  const { t } = useAdminText();
  return (
    <ResourceFormDialog title={t("payments.confirm.title")} description={t("payments.confirm.description")} submitLabel={t("payments.confirm.submit")} submitVariant="success" form={form} onClose={onClose}>
      <Restatement payment={payment} />
    </ResourceFormDialog>
  );
}

export interface RejectPaymentDialogProps {
  readonly payment: Payment;
  readonly reason: string;
  /** Already translated. */
  readonly reasonError?: string | undefined;
  readonly form: ResourceFormView;
  readonly onReasonChange: (reason: string) => void;
  readonly onReasonBlur: () => void;
  readonly onClose: () => void;
}

/** The explicit rejection of a declared transfer: it restates the payment and asks for the reason (20 to 500 characters), which is never copied to the ledger. */
export function RejectPaymentDialog({ payment, reason, reasonError, form, onReasonChange, onReasonBlur, onClose }: RejectPaymentDialogProps) {
  const { t } = useAdminText();
  return (
    <ResourceFormDialog title={t("payments.reject.title")} description={t("payments.reject.description")} submitLabel={t("payments.reject.submit")} submitVariant="destructive" form={form} onClose={onClose}>
      <Restatement payment={payment} />
      <TextAreaField
        label={t("payments.reject.reason")}
        controlId={REJECT_REASON_ID}
        required
        maxLength={REJECTION_REASON_MAX}
        description={t("payments.reject.reasonHelp", { min: REJECTION_REASON_MIN, max: REJECTION_REASON_MAX })}
        value={reason}
        error={reasonError}
        onChange={onReasonChange}
        onBlur={onReasonBlur}
      />
    </ResourceFormDialog>
  );
}
