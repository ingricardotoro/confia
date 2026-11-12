import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { OptionField, TextField } from "../../organization/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../organization/components/ResourceFormDialog.tsx";
import { EXIT_CONTROL_IDS, EXIT_KINDS, reasonRequiredFor, type ExitField, type ExitValues } from "../enrollment-forms.ts";
import { ReasonField } from "./ReasonField.tsx";

export interface ExitFormProps {
  /** Already translated: the student the enrollment is of. */
  readonly studentName: string;
  readonly values: ExitValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<ExitField, string>>>;
  readonly form: ResourceFormView;
  readonly onChange: (field: ExitField, value: string) => void;
  readonly onBlur: (field: ExitField) => void;
  readonly onClose: () => void;
}

/**
 * The form to register the exit of an enrollment, in a dialog: the kind (withdrawal, transfer to another institution or
 * graduation), the day and the reason. The reason is required for the first two and optional for a graduation, and
 * the help under it says which one applies.
 */
export function ExitForm({ studentName, values, fieldErrors, form, onChange, onBlur, onClose }: ExitFormProps) {
  const { t } = useAdminText();
  const required = reasonRequiredFor(values.status);
  const text = (field: ExitField) => ({
    controlId: EXIT_CONTROL_IDS[field],
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
      title={t("enrollments.exit.title")}
      description={t("enrollments.exit.description", { name: studentName })}
      submitLabel={t("enrollments.exit.submit")}
      form={form}
      onClose={onClose}
    >
      <OptionField
        label={t("enrollments.exit.kind")}
        required
        options={EXIT_KINDS.map((kind) => ({ value: kind, label: t(`enrollments.exit.kinds.${kind}`) }))}
        {...text("status")}
      />
      <TextField label={t("enrollments.exit.date")} kind="date" required {...text("withdrawnOn")} />
      <ReasonField
        label={t("enrollments.exit.reason")}
        required={required}
        description={t(required ? "enrollments.exit.reasonRequiredHelp" : "enrollments.exit.reasonOptionalHelp")}
        {...text("withdrawalReason")}
      />
    </ResourceFormDialog>
  );
}
