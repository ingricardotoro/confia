import { MoneyAmount } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant } from "../../../shared/format/format-date.ts";
import type { CashSession } from "../cashbox-types.ts";
import { CashSessionStatusBadge } from "./CashSessionStatusBadge.tsx";

export interface CashSessionSummaryProps {
  readonly session: CashSession;
  /** The detail names the cashier (by the identifier the server gives); the cashier's own screen does not need to. */
  readonly showCashier?: boolean;
}

function Term({ label, children }: { readonly label: string; readonly children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs font-semibold tracking-caps text-text-secondary uppercase">{label}</dt>
      <dd className="mt-0.5 text-sm font-medium text-text-primary">{children}</dd>
    </div>
  );
}

/**
 * What the server says about a session: when it opened, its float, the expected cash and, once closed, the counted cash and the difference
 * (declared minus expected, which the server computes), the reason of the difference and the approval. Every amount is shown as it
 * arrived, formatted and never computed: the screen adds, subtracts and compares nothing.
 */
export function CashSessionSummary({ session, showCashier = false }: CashSessionSummaryProps) {
  const { t } = useAdminText();
  const { close, approval } = session;
  return (
    <dl className="grid grid-cols-1 gap-x-6 gap-y-3 rounded-lg border border-border-subtle bg-bg-surface p-3 sm:grid-cols-2 lg:grid-cols-4">
      <Term label={t("cashbox.summary.status")}>
        <CashSessionStatusBadge status={session.status} />
      </Term>
      {showCashier ? (
        <Term label={t("cashbox.summary.cashier")}>
          <code className="font-identifier text-xs break-all">{session.cashierId}</code>
        </Term>
      ) : null}
      <Term label={t("cashbox.summary.openedAt")}>{formatInstant(session.openedAt)}</Term>
      {close === undefined ? null : <Term label={t("cashbox.summary.closedAt")}>{formatInstant(close.closedAt)}</Term>}
      <Term label={t("cashbox.summary.openingFloat")}>
        <MoneyAmount value={session.openingFloat} size="sm" />
      </Term>
      <Term label={t("cashbox.summary.expected")}>
        <MoneyAmount value={session.expectedAmount} size="sm" emphasis="strong" />
      </Term>
      {close === undefined ? null : (
        <>
          <Term label={t("cashbox.summary.declared")}>
            <MoneyAmount value={close.declaredAmount} size="sm" emphasis="strong" />
          </Term>
          <Term label={t("cashbox.summary.difference")}>
            <MoneyAmount value={close.differenceAmount} size="sm" emphasis="strong" colorBySign showPositiveSign />
          </Term>
          {close.differenceReason === undefined ? null : (
            <div className="sm:col-span-2 lg:col-span-4">
              <dt className="text-xs font-semibold tracking-caps text-text-secondary uppercase">{t("cashbox.summary.differenceReason")}</dt>
              <dd className="mt-0.5 text-sm text-text-primary">{close.differenceReason}</dd>
            </div>
          )}
        </>
      )}
      {approval === undefined ? null : (
        <>
          <Term label={t("cashbox.summary.approvedAt")}>{formatInstant(approval.approvedAt)}</Term>
          <div className="sm:col-span-2">
            <dt className="text-xs font-semibold tracking-caps text-text-secondary uppercase">{t("cashbox.summary.approvalNote")}</dt>
            <dd className="mt-0.5 text-sm text-text-primary">{approval.note}</dd>
          </div>
        </>
      )}
    </dl>
  );
}
