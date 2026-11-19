import { Alert, Button, Dialog, FormField, MoneyAmount, Textarea } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { StudentCharge } from "../charges-types.ts";
import { reasonLengthOf, VOID_REASON_MAX, VOID_REASON_MIN, type ReasonProblem } from "../void-model.ts";

/** The id of the reason field, so the container can move the focus to it when the person confirms with a reason that does not hold. */
export const VOID_REASON_ID = "void-charge-reason";

export interface VoidChargeDialogProps {
  /** The charge being voided: the dialog restates what it is (concept, period and total as the server gave them). */
  readonly charge: Pick<StudentCharge, "conceptName" | "conceptCode" | "periodLabel" | "total">;
  readonly reason: string;
  readonly onReasonChange: (reason: string) => void;
  /** What is wrong with the reason, once the person has left the field or tried to confirm. */
  readonly problem?: ReasonProblem | undefined;
  /** The request is in flight: the dialog does not close and the confirm button waits, so it can never be sent twice. */
  readonly busy: boolean;
  /** Already translated: why the last try was refused or did not arrive. */
  readonly failure?: { readonly message: string; readonly traceId?: string | undefined; readonly retryable: boolean } | undefined;
  readonly onReasonBlur: () => void;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
}

/**
 * The explicit confirmation of a void. It says that the void cannot be undone and posts an exact reversal in the ledger, asks for the
 * reason (with a live count, by code points like the server) and warns not to write personal data in it. It sends nothing until the
 * confirm button is pressed; while the request is in flight neither Escape, the overlay nor the cancel button closes it.
 */
export function VoidChargeDialog({ charge, reason, onReasonChange, problem, busy, failure, onReasonBlur, onConfirm, onCancel }: VoidChargeDialogProps) {
  const { t } = useAdminText();
  const concept = charge.conceptName ?? charge.conceptCode ?? t("charges.studentCharges.noConcept");
  const count = reasonLengthOf(reason);
  return (
    <Dialog
      open
      size="md"
      title={t("charges.void.title")}
      description={t("charges.void.description", { concept, period: charge.periodLabel })}
      onOpenChange={(open) => {
        if (!open && !busy) onCancel();
      }}
      footer={
        <>
          <Button variant="secondary" size="sm" disabled={busy} onClick={onCancel}>
            {t("charges.void.cancel")}
          </Button>
          <Button variant="destructive" size="sm" loading={busy} onClick={onConfirm}>
            {t("charges.void.confirm")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-text-secondary">{t("charges.void.summaryConcept")}</dt>
          <dd className="font-medium text-text-primary">{concept}</dd>
          <dt className="text-text-secondary">{t("charges.void.summaryPeriod")}</dt>
          <dd className="font-medium text-text-primary">{charge.periodLabel}</dd>
          <dt className="text-text-secondary">{t("charges.void.summaryTotal")}</dt>
          <dd className="text-right">
            <MoneyAmount value={charge.total} emphasis="strong" />
          </dd>
        </dl>
        <Alert variant="warning">
          <p>{t("charges.void.irreversible")}</p>
        </Alert>
        <FormField
          label={t("charges.void.reasonLabel")}
          controlId={VOID_REASON_ID}
          required
          description={t("charges.void.reasonHelp", { min: VOID_REASON_MIN, max: VOID_REASON_MAX })}
          {...(problem === undefined ? {} : { error: t(`charges.void.errors.${problem}`, { min: VOID_REASON_MIN, max: VOID_REASON_MAX }) })}
        >
          {(control) => (
            <Textarea
              {...control}
              rows={4}
              maxLength={VOID_REASON_MAX}
              autoComplete="off"
              autoCorrect="off"
              spellCheck={false}
              value={reason}
              readOnly={busy}
              onChange={(event) => {
                onReasonChange(event.target.value);
              }}
              onBlur={onReasonBlur}
            />
          )}
        </FormField>
        <p className="text-right text-xs text-text-secondary tabular-nums">{t("charges.void.counter", { count, max: VOID_REASON_MAX })}</p>
        <div aria-live="assertive" aria-atomic="true" className="empty:hidden">
          {failure === undefined ? null : (
            <Alert variant="destructive">
              <p>{failure.message}</p>
              {failure.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: failure.traceId })}</p>}
              {failure.retryable ? <p className="mt-1">{t("charges.void.retryNote")}</p> : null}
            </Alert>
          )}
        </div>
      </div>
    </Dialog>
  );
}
