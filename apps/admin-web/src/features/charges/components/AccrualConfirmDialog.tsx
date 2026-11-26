import { Alert, Button, Dialog, MoneyAmount } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { AccrualPreview } from "../charges-types.ts";

export interface AccrualConfirmDialogProps {
  readonly planName: string;
  readonly periodLabel: string;
  /** What the person saw: the dialog restates its count, its totals (as the server rounded them) and its errors. */
  readonly preview: AccrualPreview;
  /** The request is in flight: the dialog does not close and the confirm button waits, so it can never be sent twice. */
  readonly busy: boolean;
  /** Already translated: why the last try was refused or did not arrive. */
  readonly failure?: { readonly message: string; readonly traceId?: string | undefined; readonly retryable: boolean } | undefined;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
}

/**
 * The explicit confirmation of a generation. It says what will be posted to the ledger, and that it cannot be undone from
 * this screen, and sends nothing until the confirm button is pressed. While the request is in flight neither Escape, the
 * overlay nor the cancel button closes it, so the answer is never lost.
 */
export function AccrualConfirmDialog({ planName, periodLabel, preview, busy, failure, onConfirm, onCancel }: AccrualConfirmDialogProps) {
  const { t } = useAdminText();
  return (
    <Dialog
      open
      size="md"
      title={t("charges.accrual.confirmDialog.title", { plan: planName })}
      description={t("charges.accrual.confirmDialog.description", { period: periodLabel, count: preview.toCreate })}
      onOpenChange={(open) => {
        if (!open && !busy) onCancel();
      }}
      footer={
        <>
          <Button variant="secondary" size="sm" disabled={busy} onClick={onCancel}>
            {t("charges.accrual.confirmDialog.cancel")}
          </Button>
          <Button variant="success" size="sm" loading={busy} onClick={onConfirm}>
            {t("charges.accrual.confirmDialog.confirm")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        {preview.totals.length === 0 ? null : (
          <section aria-labelledby="accrual-confirm-totals" className="flex flex-col gap-1">
            <h3 id="accrual-confirm-totals" className="text-sm font-semibold text-text-primary">
              {t("charges.accrual.confirmDialog.totalsHeading")}
            </h3>
            <ul className="flex flex-col gap-0.5">
              {preview.totals.map((total) => (
                <li key={total.currency} className="flex flex-col">
                  <MoneyAmount value={total.total} size="lg" emphasis="strong" />
                  <span className="flex items-center gap-1 text-sm text-text-secondary">
                    {t("charges.accrual.confirmDialog.benefitLine")}
                    <MoneyAmount value={total.benefitAmount} size="sm" />
                  </span>
                </li>
              ))}
            </ul>
          </section>
        )}
        {preview.errors === 0 ? null : <p className="text-sm text-text-secondary">{t("charges.accrual.confirmDialog.errorsNote", { count: preview.errors })}</p>}
        <Alert variant="warning">
          <p>{t("charges.accrual.confirmDialog.irreversible")}</p>
        </Alert>
        <div aria-live="assertive" aria-atomic="true" className="empty:hidden">
          {failure === undefined ? null : (
            <Alert variant="destructive">
              <p>{failure.message}</p>
              {failure.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: failure.traceId })}</p>}
              {failure.retryable ? <p className="mt-1">{t("charges.accrual.confirmDialog.retryNote")}</p> : null}
            </Alert>
          )}
        </div>
      </div>
    </Dialog>
  );
}
