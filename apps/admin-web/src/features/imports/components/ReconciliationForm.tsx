import { Alert, Button, FormErrorSummary, Skeleton } from "@confia/ui";
import type { Ref, SyntheticEvent } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { OptionField, TextField, type Option } from "../../organization/components/fields.tsx";
import { MAX_REFERENCE_LENGTH, RECONCILIATION_CONTROL_IDS, type ReconciliationField, type ReconciliationValues } from "../reconciliation-model.ts";

/** What the person is told about the years of the selector: still loading, not readable, none exist, or ready to choose from. */
export type YearsState = "loading" | "failed" | "empty" | "ready";

/** The acta that was just signed, for the success message: the year by name, the reference as it was written, and the server's difference. */
export interface SignedNotice {
  readonly yearName: string;
  readonly reference: string;
  /** The wording of the server's difference, already translated. */
  readonly difference: string;
}

export interface ReconciliationFormProps {
  readonly years: { readonly state: YearsState; readonly options: readonly Option[]; readonly onRetry: () => void };
  readonly values: ReconciliationValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<ReconciliationField, string>>>;
  readonly summary: readonly { readonly controlId: string; readonly message: string }[];
  readonly alert?: { readonly message: string; readonly traceId?: string } | undefined;
  readonly countdown?: { readonly remaining: string } | undefined;
  /** The one polite region of the form: the wait while the signing is in flight, or nothing. */
  readonly liveMessage: string;
  readonly submitting: boolean;
  readonly summaryRef: Ref<HTMLDivElement>;
  readonly alertRef: Ref<HTMLDivElement>;
  readonly signed?: SignedNotice | undefined;
  readonly signedRef: Ref<HTMLDivElement>;
  readonly onChange: (field: ReconciliationField, value: string) => void;
  readonly onBlur: (field: ReconciliationField) => void;
  readonly onSubmit: (event: SyntheticEvent) => void;
}

/**
 * The form of the acta: the year (the active one is chosen by the container), the reference of the official roster and its total.
 * It makes no request. The button is never disabled for being invalid (docs/ui-ux/04): a send with a problem puts the summary
 * of errors in front, and while the signing is in flight the button is busy and says why in a polite region. The years have
 * their own four states, so an account that cannot read them still sees a screen that explains it.
 */
export function ReconciliationForm({
  years,
  values,
  fieldErrors,
  summary,
  alert,
  countdown,
  liveMessage,
  submitting,
  summaryRef,
  alertRef,
  signed,
  signedRef,
  onChange,
  onBlur,
  onSubmit,
}: ReconciliationFormProps) {
  const { t } = useAdminText();
  const text = (field: ReconciliationField) => ({
    controlId: RECONCILIATION_CONTROL_IDS[field],
    value: values[field],
    error: fieldErrors[field],
    onChange: (value: string) => {
      onChange(field, value);
    },
    onBlur: () => {
      onBlur(field);
    },
  });

  return (
    <form noValidate onSubmit={onSubmit} className="flex max-w-xl flex-col gap-4" aria-label={t("imports.reconciliation.form.label")}>
      {signed === undefined ? null : (
        <Alert ref={signedRef} tabIndex={-1} variant="info" title={t("imports.reconciliation.signed.title")}>
          <p>{t("imports.reconciliation.signed.body", { year: signed.yearName, reference: signed.reference })}</p>
          <p className="mt-1">{signed.difference}</p>
        </Alert>
      )}
      <FormErrorSummary ref={summaryRef} errors={summary} />
      {alert === undefined ? null : (
        <Alert ref={alertRef} variant="destructive">
          <p>{alert.message}</p>
          {alert.traceId === undefined ? null : <p className="mt-1">{t("organization.common.trace", { traceId: alert.traceId })}</p>}
        </Alert>
      )}
      {countdown === undefined ? null : <p className="text-sm text-text-secondary">{countdown.remaining}</p>}

      {years.state === "loading" ? (
        <div className="flex flex-col gap-2" role="status" aria-label={t("imports.reconciliation.year.loading")}>
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-9 w-full" />
        </div>
      ) : years.state === "ready" ? (
        <OptionField
          label={t("imports.reconciliation.year.label")}
          required
          placeholder={t("imports.reconciliation.year.placeholder")}
          options={years.options}
          disabled={submitting}
          {...text("academicYearId")}
        />
      ) : (
        <Alert variant={years.state === "failed" ? "destructive" : "warning"} title={t(years.state === "failed" ? "imports.reconciliation.year.failedTitle" : "imports.reconciliation.year.emptyTitle")}>
          <p>{t(years.state === "failed" ? "imports.reconciliation.year.failedDescription" : "imports.reconciliation.year.emptyDescription")}</p>
          {years.state === "failed" ? (
            <Button variant="secondary" size="sm" className="mt-2" onClick={years.onRetry}>
              {t("imports.reconciliation.year.retry")}
            </Button>
          ) : null}
        </Alert>
      )}

      <TextField
        label={t("imports.reconciliation.form.referenceLabel")}
        required
        description={t("imports.reconciliation.form.referenceHelp", { max: MAX_REFERENCE_LENGTH })}
        disabled={submitting}
        {...text("officialReference")}
      />
      <TextField
        label={t("imports.reconciliation.form.countLabel")}
        required
        kind="numeric"
        description={t("imports.reconciliation.form.countHelp")}
        disabled={submitting}
        {...text("officialEnrollmentCount")}
      />

      <p className="text-sm text-text-secondary">{t("imports.reconciliation.form.signHelp")}</p>
      <div>
        <Button type="submit" loading={submitting}>
          {t("imports.reconciliation.form.submit")}
        </Button>
      </div>
      <p aria-live="polite" aria-atomic="true" className="min-h-5 text-sm text-text-secondary">
        {liveMessage}
      </p>
    </form>
  );
}
