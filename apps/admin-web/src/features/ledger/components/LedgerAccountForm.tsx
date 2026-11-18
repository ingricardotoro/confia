import { Checkbox, Label } from "@confia/ui";
import { useId } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, OptionField, ReadOnlyField, TextField } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { ACCOUNT_CONTROL_IDS, canHoldStudentBalances, type AccountField, type AccountValues } from "../account-form.ts";
import { ACCOUNT_TYPES } from "../ledger-search.ts";

export interface LedgerAccountFormProps {
  readonly mode: "create" | "edit";
  readonly values: AccountValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<AccountField, string>>>;
  readonly form: ResourceFormView;
  readonly onChange: (field: AccountField, value: string) => void;
  readonly onBlur: (field: AccountField) => void;
  readonly onClose: () => void;
}

interface CheckFieldProps {
  readonly controlId: string;
  readonly label: string;
  readonly help: string;
  readonly checked: boolean;
  readonly disabled?: boolean;
  readonly onChange: (checked: boolean) => void;
}

/** A yes or no field: a checkbox with its label and a help text that the control is described by. */
function CheckField({ controlId, label, help, checked, disabled = false, onChange }: CheckFieldProps) {
  const helpId = useId();
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <Checkbox
          id={controlId}
          aria-describedby={helpId}
          checked={checked}
          disabled={disabled}
          onCheckedChange={(next) => {
            onChange(next === true);
          }}
        />
        <Label htmlFor={controlId}>{label}</Label>
      </div>
      <p id={helpId} className="text-sm text-text-secondary">
        {help}
      </p>
    </div>
  );
}

/**
 * The form of an account: a code, a name, a type and whether it carries the balance of each student and, when editing,
 * whether it is active. The code, the type and the flag are fixed once the account exists, because the entries of the
 * ledger refer to them; an edit shows them and changes only the name and the state.
 */
export function LedgerAccountForm({ mode, values, fieldErrors, form, onChange, onBlur, onClose }: LedgerAccountFormProps) {
  const { t } = useAdminText();
  const text = (field: AccountField) => ({
    controlId: ACCOUNT_CONTROL_IDS[field],
    value: values[field],
    error: fieldErrors[field],
    onChange: (value: string) => {
      onChange(field, value);
    },
    onBlur: () => {
      onBlur(field);
    },
  });
  const creating = mode === "create";
  const mayHold = canHoldStudentBalances(values.type);
  const knownType = ACCOUNT_TYPES.find((type) => type === values.type);

  return (
    <ResourceFormDialog
      title={t(creating ? "ledger.accounts.createTitle" : "ledger.accounts.editTitle")}
      description={t(creating ? "ledger.accounts.createDescription" : "ledger.accounts.editDescription")}
      submitLabel={t("resource.save")}
      form={form}
      onClose={onClose}
    >
      <FieldGrid>
        {creating ? (
          <TextField label={t("ledger.accounts.fields.code")} required description={t("ledger.accounts.codeHelp")} {...text("code")} />
        ) : (
          <ReadOnlyField label={t("ledger.accounts.fields.code")} controlId={ACCOUNT_CONTROL_IDS.code} value={values.code} />
        )}
        <TextField label={t("ledger.accounts.fields.name")} required {...text("name")} />
      </FieldGrid>
      {creating ? (
        <OptionField
          label={t("ledger.accounts.fields.type")}
          required
          options={ACCOUNT_TYPES.map((type) => ({ value: type, label: t(`ledger.types.${type}`) }))}
          placeholder={t("resource.chooseOption")}
          {...text("type")}
        />
      ) : (
        <ReadOnlyField
          label={t("ledger.accounts.fields.type")}
          controlId={ACCOUNT_CONTROL_IDS.type}
          value={knownType === undefined ? "" : t(`ledger.types.${knownType}`)}
        />
      )}
      {creating ? (
        <CheckField
          controlId={ACCOUNT_CONTROL_IDS.holdsStudentBalances}
          label={t("ledger.accounts.fields.holdsStudentBalances")}
          help={t(mayHold || values.type === "" ? "ledger.accounts.holdsHelp" : "ledger.accounts.holdsUnavailable")}
          checked={mayHold && values.holdsStudentBalances === "true"}
          disabled={!mayHold}
          onChange={(checked) => {
            onChange("holdsStudentBalances", checked ? "true" : "false");
          }}
        />
      ) : (
        <ReadOnlyField
          label={t("ledger.accounts.fields.holdsStudentBalances")}
          controlId={ACCOUNT_CONTROL_IDS.holdsStudentBalances}
          value={t(values.holdsStudentBalances === "true" ? "ledger.common.yes" : "ledger.common.no")}
        />
      )}
      {creating ? null : (
        <CheckField
          controlId={ACCOUNT_CONTROL_IDS.active}
          label={t("ledger.accounts.fields.active")}
          help={t("ledger.accounts.activeHelp")}
          checked={values.active === "true"}
          onChange={(checked) => {
            onChange("active", checked ? "true" : "false");
          }}
        />
      )}
    </ResourceFormDialog>
  );
}
