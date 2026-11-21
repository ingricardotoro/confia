import { Alert, Button, Card, FormError, FormErrorSummary, PageHeader } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { OptionField, TextAreaField, TextField } from "../../../shared/resource-screen/components/fields.tsx";
import { StatementStudentPicker, type StatementStudentPickerProps } from "../../../shared/student-lookups/components/StatementStudentPicker.tsx";
import { PAYMENT_METHODS } from "../payments-search.ts";
import { NOTES_MAX, isTransfer, type RegisterField } from "../payments-model.ts";
import type { Payment } from "../payments-types.ts";
import { PayableChargesPanel, type PayableChargesState, type StudentBalanceState } from "./PayableChargesPanel.tsx";
import { RegisterPaymentResult } from "./RegisterPaymentResult.tsx";

export const REGISTER_CONTROL_IDS: Readonly<Record<RegisterField, string>> = {
  studentId: "payments-register-student",
  method: "payments-register-method",
  amount: "payments-register-amount",
  receivedOn: "payments-register-received-on",
  reference: "payments-register-reference",
  bankName: "payments-register-bank",
  notes: "payments-register-notes",
};

/** Whether the person has the cashbox open, which cash needs: `unknown` while it is read, and for every method but cash. */
export type CashboxPresence = "unknown" | "open" | "none";

export interface RegisterPaymentViewProps {
  /** What the form shows of its state (the summary, the alert, the wait). */
  readonly form: ResourceFormView;
  readonly values: Readonly<Record<RegisterField, string>>;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<RegisterField, string>>>;
  readonly onChange: (field: RegisterField, value: string) => void;
  readonly onBlur: (field: RegisterField) => void;
  readonly picker: StatementStudentPickerProps;
  /** Present while a student is chosen: how to name them (the form only holds the id) and the way to choose another. */
  readonly chosen?: { readonly label: string | undefined; readonly onChange: () => void } | undefined;
  readonly charges: PayableChargesState | undefined;
  readonly balance: StudentBalanceState;
  readonly cashbox: CashboxPresence;
  /** The payment the server registered: the page shows its result in place of the form. */
  readonly result?: Payment | undefined;
  readonly onGoToCashbox: () => void;
  readonly onViewCharges: () => void;
  readonly onNew: () => void;
  readonly onViewPayment: (payment: Payment) => void;
  readonly onViewStatement: (payment: Payment) => void;
}

const NO_SESSION_ID = "payments-register-no-session";

/**
 * The form that registers a payment: the student and what they owe (the server own figures), the method, the amount, the day and, for a transfer,
 * the reference and the bank. Cash with no open cashbox says so and offers the way to it. The button is never disabled for being invalid; while the
 * request is in flight it waits, so the same try cannot be sent twice. When the server answers, its result replaces the form: what was applied to
 * each charge and what stayed in favor. Nothing here adds, splits or rounds an amount.
 */
export function RegisterPaymentView({
  form: { summary, alert, countdown, liveMessage, submitting, summaryRef, alertRef, onSubmit },
  values,
  fieldErrors,
  onChange,
  onBlur,
  picker,
  chosen,
  charges,
  balance,
  cashbox,
  result,
  onGoToCashbox,
  onViewCharges,
  onNew,
  onViewPayment,
  onViewStatement,
}: RegisterPaymentViewProps) {
  const { t } = useAdminText();
  const transfer = isTransfer(values.method);
  const noSession = !transfer && cashbox === "none";
  const bind = (field: RegisterField) => ({
    controlId: REGISTER_CONTROL_IDS[field],
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
    <section aria-labelledby="payments-register-heading" className="flex flex-col gap-3">
      <PageHeader title={t("payments.register.heading")} headingLevel={2} headingId="payments-register-heading" description={t("payments.register.description")} />
      {result !== undefined ? (
        <RegisterPaymentResult
          payment={result}
          onNew={onNew}
          onViewPayment={() => {
            onViewPayment(result);
          }}
          onViewStatement={() => {
            onViewStatement(result);
          }}
        />
      ) : (
        <>
          <Card>
            <div className="flex flex-col gap-3">
              {chosen === undefined ? (
                <div className="flex flex-col gap-1.5">
                  <StatementStudentPicker {...picker} />
                  {fieldErrors.studentId === undefined ? null : <FormError id="payments-register-student-error">{fieldErrors.studentId}</FormError>}
                </div>
              ) : (
                <div role="group" aria-label={t("charges.statement.picker.chosen")} className="flex flex-col gap-1.5">
                  <p className="text-sm font-medium text-text-primary">{t("charges.statement.picker.label")}</p>
                  <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border-subtle px-3 py-2">
                    <p className="text-sm font-medium text-text-primary">{chosen.label ?? t("charges.studentCharges.studentUnnamed")}</p>
                    <Button variant="secondary" size="sm" onClick={chosen.onChange}>
                      {t("charges.studentCharges.changeStudent")}
                    </Button>
                  </div>
                </div>
              )}
              {charges === undefined ? null : <PayableChargesPanel charges={charges} balance={balance} onViewCharges={onViewCharges} />}
            </div>
          </Card>
          <form noValidate onSubmit={onSubmit} className="flex max-w-xl flex-col gap-3" aria-label={t("payments.register.heading")}>
            <FormErrorSummary ref={summaryRef} errors={summary} />
            {alert === undefined ? null : (
              <Alert ref={alertRef} variant="destructive">
                <p>{alert.message}</p>
                {alert.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: alert.traceId })}</p>}
              </Alert>
            )}
            {countdown === undefined ? null : <p className="text-sm text-text-secondary">{countdown.remaining}</p>}
            <OptionField
              label={t("payments.register.method")}
              required
              description={t(transfer ? "payments.register.transferHelp" : "payments.register.cashHelp")}
              options={PAYMENT_METHODS.map((method) => ({ value: method, label: t(`payments.methods.${method}`) }))}
              {...bind("method")}
            />
            {noSession ? (
              <Alert variant="warning" id={NO_SESSION_ID}>
                <p className="font-medium">{t("payments.register.noSession.title")}</p>
                <p className="mt-1">{t("payments.register.noSession.description")}</p>
                <Button variant="secondary" size="sm" className="mt-2" onClick={onGoToCashbox}>
                  {t("payments.register.noSession.action")}
                </Button>
              </Alert>
            ) : null}
            <TextField label={t("payments.register.amount")} required kind="numeric" description={t("payments.register.amountHelp")} {...bind("amount")} />
            <TextField label={t("payments.register.receivedOn")} required kind="date" description={t("payments.register.receivedOnHelp")} {...bind("receivedOn")} />
            {transfer ? (
              <>
                <TextField label={t("payments.register.reference")} required description={t("payments.register.referenceHelp")} {...bind("reference")} />
                <TextField label={t("payments.register.bankName")} required description={t("payments.register.bankNameHelp")} {...bind("bankName")} />
              </>
            ) : null}
            <TextAreaField label={t("payments.register.notes")} maxLength={NOTES_MAX} description={t("payments.register.notesHelp")} {...bind("notes")} />
            <div>
              <Button type="submit" loading={submitting} disabled={noSession} {...(noSession ? { "aria-describedby": NO_SESSION_ID } : {})}>
                {t("payments.register.submit")}
              </Button>
            </div>
            <p aria-live="polite" aria-atomic="true" className="text-sm text-text-secondary">
              {liveMessage}
            </p>
          </form>
        </>
      )}
    </section>
  );
}

