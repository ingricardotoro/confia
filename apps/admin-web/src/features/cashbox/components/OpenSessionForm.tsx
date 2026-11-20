import { Alert, Button, Card, FormErrorSummary } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { TextField } from "../../../shared/resource-screen/components/fields.tsx";

export const OPENING_FLOAT_ID = "cashbox-opening-float";

export interface OpenSessionFormProps {
  readonly form: ResourceFormView;
  /** The float as typed: a text the server judges and rounds, never a number here. */
  readonly openingFloat: string;
  /** Already translated. */
  readonly error?: string | undefined;
  readonly onChange: (value: string) => void;
  readonly onBlur: () => void;
}

/**
 * The form that opens the cashier's session: one amount, the cash in the drawer. The button is never disabled for being invalid; while the
 * request is in flight it waits, so the same try cannot be sent twice. A refusal of the server is told in the alert (a session already open,
 * for one) and keeps what was typed.
 */
export function OpenSessionForm({ form: { summary, alert, countdown, liveMessage, submitting, summaryRef, alertRef, onSubmit }, openingFloat, error, onChange, onBlur }: OpenSessionFormProps) {
  const { t } = useAdminText();
  return (
    <Card title={t("cashbox.open.title")} description={t("cashbox.open.description")}>
      <form noValidate onSubmit={onSubmit} className="flex max-w-md flex-col gap-3">
        <FormErrorSummary ref={summaryRef} errors={summary} />
        {alert === undefined ? null : (
          <Alert ref={alertRef} variant="destructive">
            <p>{alert.message}</p>
            {alert.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: alert.traceId })}</p>}
          </Alert>
        )}
        {countdown === undefined ? null : <p className="text-sm text-text-secondary">{countdown.remaining}</p>}
        <TextField
          label={t("cashbox.open.floatLabel")}
          controlId={OPENING_FLOAT_ID}
          required
          kind="numeric"
          description={t("cashbox.open.floatHelp")}
          value={openingFloat}
          error={error}
          onChange={onChange}
          onBlur={onBlur}
        />
        <div>
          <Button type="submit" loading={submitting}>
            {t("cashbox.open.submit")}
          </Button>
        </div>
        <p aria-live="polite" aria-atomic="true" className="text-sm text-text-secondary">
          {liveMessage}
        </p>
      </form>
    </Card>
  );
}
