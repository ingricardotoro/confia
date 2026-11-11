import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { OptionField, ReadOnlyField, TextField, type Option } from "../../components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../components/ResourceFormDialog.tsx";
import { GRADE_CONTROL_IDS, type GradeField, type GradeValues } from "../grade-form.ts";

export interface GradeFormProps {
  readonly mode: "create" | "edit";
  readonly values: GradeValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<GradeField, string>>>;
  /** The active modalities, to choose one for a new grade. */
  readonly modalityOptions: readonly Option[];
  /** The name of the modality of the grade being edited, shown and not editable. */
  readonly modalityName: string;
  readonly form: ResourceFormView;
  readonly onChange: (field: GradeField, value: string) => void;
  readonly onBlur: (field: GradeField) => void;
  readonly onClose: () => void;
}

/** The form of a grade: its modality (chosen when new, fixed afterwards), a name, an order and, when editing, its state. */
export function GradeForm({ mode, values, fieldErrors, modalityOptions, modalityName, form, onChange, onBlur, onClose }: GradeFormProps) {
  const { t } = useAdminText();
  const text = (field: GradeField) => ({
    controlId: GRADE_CONTROL_IDS[field],
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
      title={t(mode === "create" ? "organization.grades.createTitle" : "organization.grades.editTitle")}
      description={t(mode === "create" ? "organization.grades.createDescription" : "organization.grades.editDescription")}
      submitLabel={t("organization.common.save")}
      form={form}
      onClose={onClose}
    >
      {mode === "create" ? (
        <OptionField
          label={t("organization.grades.fields.modalityId")}
          required
          options={modalityOptions}
          placeholder={t("organization.common.chooseOption")}
          {...text("modalityId")}
        />
      ) : (
        <ReadOnlyField label={t("organization.grades.fields.modalityId")} controlId={GRADE_CONTROL_IDS.modalityId} value={modalityName} />
      )}
      <TextField label={t("organization.grades.fields.name")} required {...text("name")} />
      <TextField label={t("organization.grades.fields.ordinal")} kind="numeric" required {...text("ordinal")} />
      {mode === "edit" ? (
        <OptionField
          label={t("organization.grades.fields.isActive")}
          options={[
            { value: "true", label: t("organization.common.active") },
            { value: "false", label: t("organization.common.inactive") },
          ]}
          {...text("isActive")}
        />
      ) : null}
    </ResourceFormDialog>
  );
}
