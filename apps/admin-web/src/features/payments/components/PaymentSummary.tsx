import { MoneyAmount } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant, formatIsoDate } from "../../../shared/format/format-date.ts";
import type { Payment } from "../payments-types.ts";
import { PaymentStatusBadge } from "./PaymentStatusBadge.tsx";

/**
 * What the server says of one payment, as a list of facts: who paid for which student, how, how much, when, the state, what the payment applied to
 * charges and what stayed in favor of the student (the two sums are the server own, never added here), and the decision of a transfer.
 * A transfer that is still declared has applied nothing, so those two lines are left out until it is confirmed.
 */
export function PaymentSummary({ payment }: { readonly payment: Payment }) {
  const { t } = useAdminText();
  const none = t("payments.detail.noValue");
  const { decision } = payment;
  const posted = payment.status === "confirmed";
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5 text-sm">
      <dt className="text-text-secondary">{t("payments.detail.fields.student")}</dt>
      <dd>{payment.studentCode ?? none}</dd>
      <dt className="text-text-secondary">{t("payments.detail.fields.method")}</dt>
      <dd>{t(`payments.methods.${payment.method}`)}</dd>
      <dt className="text-text-secondary">{t("payments.detail.fields.status")}</dt>
      <dd>
        <PaymentStatusBadge status={payment.status} />
      </dd>
      <dt className="text-text-secondary">{t("payments.detail.fields.amount")}</dt>
      <dd>
        <MoneyAmount value={payment.amount} size="sm" emphasis="strong" />
      </dd>
      <dt className="text-text-secondary">{t("payments.detail.fields.receivedOn")}</dt>
      <dd>{formatIsoDate(payment.receivedOn)}</dd>
      <dt className="text-text-secondary">{t("payments.detail.fields.registeredAt")}</dt>
      <dd>{formatInstant(payment.registeredAt)}</dd>
      {payment.method === "BANK_TRANSFER" ? (
        <>
          <dt className="text-text-secondary">{t("payments.detail.fields.reference")}</dt>
          <dd>{payment.reference ?? none}</dd>
          <dt className="text-text-secondary">{t("payments.detail.fields.bankName")}</dt>
          <dd>{payment.bankName ?? none}</dd>
        </>
      ) : null}
      {payment.notes == null ? null : (
        <>
          <dt className="text-text-secondary">{t("payments.detail.fields.notes")}</dt>
          <dd>{payment.notes}</dd>
        </>
      )}
      {posted ? (
        <>
          <dt className="text-text-secondary">{t("payments.detail.fields.allocated")}</dt>
          <dd>
            <MoneyAmount value={payment.allocated} size="sm" />
          </dd>
          <dt className="text-text-secondary">{t("payments.detail.fields.unallocated")}</dt>
          <dd>
            <MoneyAmount value={payment.unallocated} size="sm" />
          </dd>
        </>
      ) : null}
      {decision === undefined ? null : (
        <>
          <dt className="text-text-secondary">{t("payments.detail.fields.decision")}</dt>
          <dd>{t(`payments.detail.decisions.${decision.kind}`, { date: formatInstant(decision.decidedAt) })}</dd>
          {decision.reason === undefined ? null : (
            <>
              <dt className="text-text-secondary">{t("payments.detail.fields.decisionReason")}</dt>
              <dd>{decision.reason}</dd>
            </>
          )}
        </>
      )}
    </dl>
  );
}
