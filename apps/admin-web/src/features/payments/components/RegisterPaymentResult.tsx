import { Alert, Button, MoneyAmount } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import type { Payment } from "../payments-types.ts";
import { AllocationsTable } from "./AllocationsTable.tsx";
import { PaymentStatusBadge } from "./PaymentStatusBadge.tsx";

export interface RegisterPaymentResultProps {
  /** The payment as the server answered it: its state, and for a confirmed one the allocations and what stayed in favor of the student. */
  readonly payment: Payment;
  readonly onNew: () => void;
  readonly onViewPayment: () => void;
  readonly onViewStatement: () => void;
}

/**
 * The result of a registered payment (docs/ui-ux/04, section 4.3: a financial form ends in a result screen, not in a toast). It shows what the
 * server did: for cash, the charges it covered and what stayed in favor; for a transfer, that it is declared and has changed nothing yet. The
 * parts are the server own: the browser neither splits the payment nor adds them up.
 */
export function RegisterPaymentResult({ payment, onNew, onViewPayment, onViewStatement }: RegisterPaymentResultProps) {
  const { t } = useAdminText();
  const confirmed = payment.status === "confirmed";
  return (
    <section aria-labelledby="payment-result-heading" className="flex flex-col gap-3">
      <Alert variant={confirmed ? "info" : "warning"}>
        <h3 id="payment-result-heading" className="font-medium">
          {t(confirmed ? "payments.register.result.titleConfirmed" : "payments.register.result.titleDeclared")}
        </h3>
        <p className="mt-1">{t(confirmed ? "payments.register.result.confirmedNote" : "payments.register.result.declaredNote")}</p>
      </Alert>
      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5 text-sm">
        <dt className="text-text-secondary">{t("payments.detail.fields.student")}</dt>
        <dd>{payment.studentCode ?? t("payments.detail.noValue")}</dd>
        <dt className="text-text-secondary">{t("payments.detail.fields.method")}</dt>
        <dd>{t(`payments.methods.${payment.method}`)}</dd>
        <dt className="text-text-secondary">{t("payments.detail.fields.status")}</dt>
        <dd>
          <PaymentStatusBadge status={payment.status} />
        </dd>
        <dt className="text-text-secondary">{t("payments.detail.fields.amount")}</dt>
        <dd>
          <MoneyAmount value={payment.amount} size="md" emphasis="strong" />
        </dd>
        <dt className="text-text-secondary">{t("payments.detail.fields.receivedOn")}</dt>
        <dd>{formatIsoDate(payment.receivedOn)}</dd>
        {confirmed ? (
          <>
            <dt className="text-text-secondary">{t("payments.detail.fields.allocated")}</dt>
            <dd>
              <MoneyAmount value={payment.allocated} size="sm" />
            </dd>
            <dt className="text-text-secondary">{t("payments.detail.fields.unallocated")}</dt>
            <dd>
              <MoneyAmount value={payment.unallocated} size="sm" emphasis="strong" />
            </dd>
          </>
        ) : null}
      </dl>
      {confirmed ? (
        <>
          <h3 className="text-base font-semibold text-text-primary">{t("payments.detail.allocationsHeading")}</h3>
          <AllocationsTable allocations={payment.allocations} emptyAction={{ label: t("payments.register.result.viewStatement"), onAction: onViewStatement }} />
        </>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Button onClick={onNew}>{t("payments.register.result.newPayment")}</Button>
        <Button variant="secondary" onClick={onViewPayment}>
          {t("payments.register.result.viewPayment")}
        </Button>
        <Button variant="secondary" onClick={onViewStatement}>
          {t("payments.register.result.viewStatement")}
        </Button>
      </div>
    </section>
  );
}
