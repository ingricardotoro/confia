import { MoneyAmount } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { TextAreaField, TextField } from "../../../shared/resource-screen/components/fields.tsx";
import { CASH_NOTE_MAX, CASH_REASON_MAX, CASH_REASON_MIN } from "../cashbox-model.ts";
import type { CashMovementType, CashSession } from "../cashbox-types.ts";

export type MovementField = "amount" | "notes";

export const MOVEMENT_CONTROL_IDS: Readonly<Record<MovementField, string>> = {
  amount: "cashbox-movement-amount",
  notes: "cashbox-movement-notes",
};

export interface MovementDialogProps {
  readonly type: CashMovementType;
  /** The expected cash as the server says it now, so the person sees what the movement acts on. */
  readonly expected: CashSession["expectedAmount"];
  readonly values: Readonly<Record<MovementField, string>>;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<MovementField, string>>>;
  readonly form: ResourceFormView;
  readonly onChange: (field: MovementField, value: string) => void;
  readonly onBlur: (field: MovementField) => void;
  readonly onClose: () => void;
}

/**
 * The form of a deposit, a withdrawal or an adjustment of the open session: the amount (a text the server judges and rounds) and a note
 * that an adjustment must have. The server refuses a withdrawal that would leave the expected cash below zero; the answer is told in the dialog.
 */
export function MovementDialog({ type, expected, values, fieldErrors, form, onChange, onBlur, onClose }: MovementDialogProps) {
  const { t } = useAdminText();
  const bind = (field: MovementField) => ({
    controlId: MOVEMENT_CONTROL_IDS[field],
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
    <ResourceFormDialog
      title={t(`cashbox.movementForm.titles.${type}`)}
      description={t(`cashbox.movementForm.descriptions.${type}`)}
      submitLabel={t(`cashbox.movementForm.submit.${type}`)}
      submitVariant={type === "withdrawal" ? "destructive" : "primary"}
      form={form}
      onClose={onClose}
    >
      <p className="text-sm text-text-secondary">
        {t("cashbox.movementForm.expectedNow")} <MoneyAmount value={expected} size="sm" emphasis="strong" />
      </p>
      <TextField label={t("cashbox.movementForm.amount")} required kind="numeric" description={t("cashbox.movementForm.amountHelp")} {...bind("amount")} />
      <TextAreaField
        label={t("cashbox.movementForm.notes")}
        required={type === "adjustment"}
        maxLength={type === "adjustment" ? CASH_REASON_MAX : CASH_NOTE_MAX}
        description={
          type === "adjustment"
            ? t("cashbox.movementForm.adjustmentNotesHelp", { min: CASH_REASON_MIN, max: CASH_REASON_MAX })
            : t("cashbox.movementForm.notesHelp", { max: CASH_NOTE_MAX })
        }
        {...bind("notes")}
      />
    </ResourceFormDialog>
  );
}
