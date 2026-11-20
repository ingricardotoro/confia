import { Alert, MoneyAmount } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { TextAreaField, TextField } from "../../../shared/resource-screen/components/fields.tsx";
import { CASH_REASON_MAX, CASH_REASON_MIN } from "../cashbox-model.ts";
import type { CashSession } from "../cashbox-types.ts";

export type CloseField = "declaredAmount" | "differenceReason";

export const CLOSE_CONTROL_IDS: Readonly<Record<CloseField, string>> = {
  declaredAmount: "cashbox-close-declared",
  differenceReason: "cashbox-close-reason",
};

export interface CloseSessionDialogProps {
  /** The expected cash as the server says it now. It is shown beside the count, and the screen compares nothing: the server computes the difference when the session closes. */
  readonly expected: CashSession["expectedAmount"];
  readonly values: Readonly<Record<CloseField, string>>;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<CloseField, string>>>;
  readonly form: ResourceFormView;
  readonly onChange: (field: CloseField, value: string) => void;
  readonly onBlur: (field: CloseField) => void;
  readonly onClose: () => void;
}

/**
 * The close of the session. The expected cash and the counted cash sit side by side; the difference is not shown here, because the browser does
 * no money arithmetic: the server computes it when the person confirms and the screen then shows what it answered. The reason of a difference
 * is a field of its own: when the server says it is needed, its message appears under the field and the dialog stays open with what was typed.
 */
export function CloseSessionDialog({ expected, values, fieldErrors, form, onChange, onBlur, onClose }: CloseSessionDialogProps) {
  const { t } = useAdminText();
  const bind = (field: CloseField) => ({
    controlId: CLOSE_CONTROL_IDS[field],
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
    <ResourceFormDialog title={t("cashbox.close.title")} description={t("cashbox.close.description")} submitLabel={t("cashbox.close.submit")} form={form} onClose={onClose}>
      <Alert variant="warning">
        <p>{t("cashbox.close.irreversible")}</p>
      </Alert>
      <div className="grid grid-cols-1 items-start gap-3 sm:grid-cols-2">
        <div>
          <p className="text-sm font-medium text-text-primary">{t("cashbox.close.expected")}</p>
          <p className="mt-1.5">
            <MoneyAmount value={expected} size="lg" emphasis="strong" />
          </p>
          <p className="mt-1 text-xs text-text-secondary">{t("cashbox.close.expectedHelp")}</p>
        </div>
        <TextField label={t("cashbox.close.declared")} required kind="numeric" description={t("cashbox.close.declaredHelp")} {...bind("declaredAmount")} />
      </div>
      <TextAreaField
        label={t("cashbox.close.reason")}
        maxLength={CASH_REASON_MAX}
        description={t("cashbox.close.reasonHelp", { min: CASH_REASON_MIN, max: CASH_REASON_MAX })}
        {...bind("differenceReason")}
      />
    </ResourceFormDialog>
  );
}
