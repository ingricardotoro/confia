import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { OptionField, TextField } from "../../components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../components/ResourceFormDialog.tsx";
import { MODALITY_CONTROL_IDS, type ModalityField, type ModalityValues } from "../modality-form.ts";

export interface ModalityFormProps {
  readonly mode: "create" | "edit";
  readonly values: ModalityValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<ModalityField, string>>>;
  readonly form: ResourceFormView;
  readonly onChange: (field: ModalityField, value: string) => void;
  readonly onBlur: (field: ModalityField) => void;
  readonly onClose: () => void;
}

/** The form of a modality: a code, a name and, when editing, its state. */
export function ModalityForm({ mode, values, fieldErrors, form, onChange, onBlur, onClose }: ModalityFormProps) {
  const { t } = useAdminText();
  const text = (field: ModalityField) => ({
    controlId: MODALITY_CONTROL_IDS[field],
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
      title={t(mode === "create" ? "organization.modalities.createTitle" : "organization.modalities.editTitle")}
      description={t(mode === "create" ? "organization.modalities.createDescription" : "organization.modalities.editDescription")}
      submitLabel={t("organization.common.save")}
      form={form}
      onClose={onClose}
    >
      <TextField label={t("organization.modalities.fields.code")} required {...text("code")} />
      <TextField label={t("organization.modalities.fields.name")} required {...text("name")} />
      {mode === "edit" ? (
        <OptionField
          label={t("organization.modalities.fields.isActive")}
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
