import { Alert, Button, Dialog, FormErrorSummary } from "@confia/ui";
import { useId, type ReactNode, type Ref, type SyntheticEvent } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";

/** What the dialog needs of the form behind it. Texts arrive already translated. */
export interface ResourceFormView {
  readonly summary: readonly { readonly controlId: string; readonly message: string }[];
  readonly alert?: { readonly message: string; readonly traceId?: string };
  /** The wait after a 429, already translated. It is outside every live region: a number that changes each second must not be announced. */
  readonly countdown?: { readonly remaining: string };
  /** The one polite region of the form: the wait while the request is in flight, or nothing. */
  readonly liveMessage: string;
  readonly submitting: boolean;
  readonly summaryRef: Ref<HTMLDivElement>;
  readonly alertRef: Ref<HTMLDivElement>;
  readonly onSubmit: (event: SyntheticEvent) => void;
}

export interface ResourceFormDialogProps {
  readonly title: string;
  readonly description: string;
  readonly submitLabel: string;
  readonly form: ResourceFormView;
  readonly onClose: () => void;
  /** The fields, in one column. */
  readonly children: ReactNode;
}

/**
 * A create or edit form in a dialog (docs/ui-ux/04, section 4): the error summary after a failed submit, the
 * alert of a failure the fields do not explain, one column of fields, and a submit button that is never disabled for
 * being invalid. While the request is in flight the dialog does not close, so the answer is never lost.
 */
export function ResourceFormDialog({
  title,
  description,
  submitLabel,
  form: { summary, alert, countdown, liveMessage, submitting, summaryRef, alertRef, onSubmit },
  onClose,
  children,
}: ResourceFormDialogProps) {
  const { t } = useAdminText();
  const formId = useId();

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !submitting) onClose();
      }}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="secondary" disabled={submitting} onClick={onClose}>
            {t("organization.common.cancel")}
          </Button>
          <Button type="submit" form={formId} loading={submitting}>
            {submitLabel}
          </Button>
        </>
      }
    >
      <form id={formId} noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <FormErrorSummary ref={summaryRef} errors={summary} />
        {alert === undefined ? null : (
          <Alert ref={alertRef} variant="destructive">
            <p>{alert.message}</p>
            {alert.traceId === undefined ? null : (
              <p className="mt-1">{t("organization.common.trace", { traceId: alert.traceId })}</p>
            )}
          </Alert>
        )}
        {countdown === undefined ? null : <p className="text-sm text-text-secondary">{countdown.remaining}</p>}
        {children}
        <p aria-live="polite" aria-atomic="true" className="text-sm text-text-secondary">
          {liveMessage}
        </p>
      </form>
    </Dialog>
  );
}
