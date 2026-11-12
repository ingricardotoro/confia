import { Alert, Button, Checkbox, Dialog, ErrorState, FormErrorSummary, FormField, Input, Label, Select, Skeleton } from "@confia/ui";
import { useId } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { RELATIONSHIPS, choiceNameOf, linkControlId, linkRemoveId, type LinkDraft } from "../link-model.ts";
import type { LinksFormView } from "../use-links-form.ts";
import { GuardianPicker, type GuardianPickerProps } from "./GuardianPicker.tsx";

export type StudentGuardiansState =
  | { readonly kind: "loading" }
  | { readonly kind: "failed"; readonly description: string; readonly onRetry: () => void }
  | {
      readonly kind: "ready";
      /** The sum the server calculated for the set it holds, already written for the person. */
      readonly savedTotal: string;
      /** The student has links now: saving an empty set removes them, and the person is told. */
      readonly hadLinks: boolean;
      readonly form: LinksFormView;
      readonly picker: GuardianPickerProps;
    };

export interface StudentGuardiansDialogProps {
  /** Names the dialog. Names live in the DOM only. */
  readonly studentName: string;
  readonly state: StudentGuardiansState;
  readonly onChange: (key: string, patch: Partial<Pick<LinkDraft, "relationship" | "percent" | "primary" | "billing">>) => void;
  readonly onRemove: (key: string) => void;
  readonly onClose: () => void;
}

interface RowProps {
  readonly row: LinkDraft;
  readonly errors: LinksFormView["rowErrors"][string] | undefined;
  readonly onChange: StudentGuardiansDialogProps["onChange"];
  readonly onRemove: StudentGuardiansDialogProps["onRemove"];
}

function LinkRow({ row, errors, onChange, onRemove }: RowProps) {
  const { t } = useAdminText();
  const name = choiceNameOf(row.guardian);
  const primaryId = `link-primary-${row.key}`;
  const billingId = `link-billing-${row.key}`;
  return (
    <li>
      <fieldset className="flex flex-col gap-3 rounded-md border border-border-subtle p-3">
        <legend className="px-1 text-sm font-semibold text-text-primary">{t("guardians.links.rowLegend", { name, code: row.guardian.guardianCode })}</legend>
        {row.guardian.status === "INACTIVE" ? <Alert variant="warning">{t("guardians.links.inactiveGuardian")}</Alert> : null}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <FormField
            label={t("guardians.links.relationship")}
            controlId={linkControlId(row.key, "relationship")}
            required
            {...(errors?.relationship === undefined ? {} : { error: errors.relationship })}
          >
            {(control) => (
              <Select
                {...control}
                value={row.relationship}
                onChange={(event) => {
                  onChange(row.key, { relationship: event.target.value });
                }}
              >
                <option value="">{t("guardians.links.chooseRelationship")}</option>
                {RELATIONSHIPS.map((relationship) => (
                  <option key={relationship} value={relationship}>
                    {t(`guardians.relationship.${relationship}`)}
                  </option>
                ))}
              </Select>
            )}
          </FormField>
          <FormField
            label={t("guardians.links.percent")}
            controlId={linkControlId(row.key, "percent")}
            required
            description={t("guardians.links.percentHelp")}
            {...(errors?.percent === undefined ? {} : { error: errors.percent })}
          >
            {(control) => (
              <Input
                {...control}
                type="text"
                inputMode="decimal"
                autoComplete="off"
                maxLength={6}
                className="text-right tabular-nums"
                value={row.percent}
                onChange={(event) => {
                  onChange(row.key, { percent: event.target.value.trim() });
                }}
              />
            )}
          </FormField>
        </div>
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
          <div className="flex items-center gap-2">
            <Checkbox
              id={primaryId}
              checked={row.primary}
              onCheckedChange={(checked) => {
                onChange(row.key, { primary: checked === true });
              }}
            />
            <Label htmlFor={primaryId}>{t("guardians.links.primaryPayer")}</Label>
          </div>
          <div className="flex items-center gap-2">
            <Checkbox
              id={billingId}
              checked={row.billing}
              onCheckedChange={(checked) => {
                onChange(row.key, { billing: checked === true });
              }}
            />
            <Label htmlFor={billingId}>{t("guardians.links.billingRecipient")}</Label>
          </div>
          <Button
            id={linkRemoveId(row.key)}
            variant="secondary"
            size="sm"
            className="ml-auto"
            aria-label={t("guardians.links.removeFor", { name })}
            onClick={() => {
              onRemove(row.key);
            }}
          >
            {t("guardians.links.remove")}
          </Button>
        </div>
      </fieldset>
    </li>
  );
}

interface ReadyFormProps {
  readonly formId: string;
  readonly savedTotal: string;
  readonly hadLinks: boolean;
  readonly picker: GuardianPickerProps;
  readonly form: LinksFormView;
  readonly onChange: StudentGuardiansDialogProps["onChange"];
  readonly onRemove: StudentGuardiansDialogProps["onRemove"];
}

/** The form of the set: the summary and the alert of a failed send, the rows, the search that adds one and the polite region. */
function ReadyForm({
  formId,
  savedTotal,
  hadLinks,
  picker,
  form: { rows, rowErrors, summary, alert, countdown, liveMessage, attachSummary, attachAlert, onSubmit },
  onChange,
  onRemove,
}: ReadyFormProps) {
  const { t } = useAdminText();
  return (
    <form id={formId} noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
      <FormErrorSummary ref={attachSummary} errors={summary} />
      {alert === undefined ? null : (
        <Alert ref={attachAlert} variant="destructive">
          <p>{alert.message}</p>
          {alert.traceId === undefined ? null : <p className="mt-1">{t("organization.common.trace", { traceId: alert.traceId })}</p>}
        </Alert>
      )}
      {countdown === undefined ? null : <p className="text-sm text-text-secondary">{countdown.remaining}</p>}
      <p className="text-sm text-text-secondary">{t("guardians.links.rule")}</p>
      <p className="text-sm font-medium text-text-primary">{t("guardians.links.savedTotal", { percent: savedTotal })}</p>
      {rows.length === 0 ? (
        <p className="text-sm text-text-secondary">{t("guardians.links.noGuardians")}</p>
      ) : (
        <ul aria-label={t("guardians.links.listLabel")} className="flex flex-col gap-3">
          {rows.map((row) => (
            <LinkRow key={row.key} row={row} errors={rowErrors[row.key]} onChange={onChange} onRemove={onRemove} />
          ))}
        </ul>
      )}
      {rows.length === 0 && hadLinks ? <Alert variant="warning">{t("guardians.links.removeAllWarning")}</Alert> : null}
      <GuardianPicker {...picker} />
      <p aria-live="polite" aria-atomic="true" className="text-sm text-text-secondary">
        {liveMessage}
      </p>
    </form>
  );
}

/**
 * The dialog that manages the whole set of guardians of one student: the rows (guardian, parentesco, percentage, primary
 * payer, billing recipient), the search that adds a guardian, and the sum the server holds. It never adds the
 * percentages: the sum shown is the one the server calculated for what is saved, and the rule that they must reach 100.00
 * is stated and judged by the server, whose reason (sum, repeated payer, repeated recipient, repeated guardian) the alert
 * shows. Loading is a skeleton with the shape of the rows, a failure offers a retry, and an empty set says how to start.
 */
export function StudentGuardiansDialog({ studentName, state, onChange, onRemove, onClose }: StudentGuardiansDialogProps) {
  const { t } = useAdminText();
  const formId = useId();
  const ready = state.kind === "ready" ? state : undefined;
  const submitting = ready?.form.submitting === true;

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !submitting) onClose();
      }}
      title={t("guardians.links.title", { name: studentName })}
      description={t("guardians.links.description")}
      className="max-w-3xl"
      footer={
        <>
          <Button variant="secondary" disabled={submitting} onClick={onClose}>
            {t("organization.common.cancel")}
          </Button>
          {ready === undefined ? null : (
            <Button type="submit" form={formId} loading={submitting}>
              {t("guardians.links.save")}
            </Button>
          )}
        </>
      }
    >
      {state.kind === "loading" ? (
        <div aria-busy="true" role="status" className="flex flex-col gap-3">
          <p className="sr-only">{t("guardians.links.loading")}</p>
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      ) : null}
      {state.kind === "failed" ? <ErrorState title={t("guardians.links.loadFailed")} description={state.description} onRetry={state.onRetry} /> : null}
      {ready === undefined ? null : (
        <ReadyForm
          formId={formId}
          savedTotal={ready.savedTotal}
          hadLinks={ready.hadLinks}
          picker={ready.picker}
          form={ready.form}
          onChange={onChange}
          onRemove={onRemove}
        />
      )}
    </Dialog>
  );
}
