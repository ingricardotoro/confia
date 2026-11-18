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
  /** Width of the dialog by purpose; a form of short fields keeps the default. */
  readonly size?: "sm" | "md" | "lg";
  /** The colour of the submit button follows its action: the brand blue by default, red for an irreversible one, green for a positive state change. */
  readonly submitVariant?: "primary" | "destructive" | "success";
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
  size = "md",
  submitVariant = "primary",
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
      size={size}
      title={title}
      description={description}
      footer={
        <>
          <Button variant="secondary" size="sm" disabled={submitting} onClick={onClose}>
            {t("resource.cancel")}
          </Button>
          <Button type="submit" size="sm" variant={submitVariant} form={formId} loading={submitting}>
            {submitLabel}
          </Button>
        </>
      }
    >
      <form id={formId} noValidate onSubmit={onSubmit} className="flex flex-col gap-3">
        <FormErrorSummary ref={summaryRef} errors={summary} />
        {alert === undefined ? null : (
          <Alert ref={alertRef} variant="destructive">
            <p>{alert.message}</p>
            {alert.traceId === undefined ? null : (
              <p className="mt-1">{t("resource.trace", { traceId: alert.traceId })}</p>
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
