import type { AdminKey } from "../../../../i18n/resources.ts";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { OptionField, TextField } from "../../components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../components/ResourceFormDialog.tsx";
import { YEAR_CONTROL_IDS, type YearField, type YearValues } from "../academic-year-form.ts";

export const STATUS_KEYS: Readonly<Record<"PLANNED" | "ACTIVE" | "CLOSED", AdminKey>> = {
  PLANNED: "organization.academicYears.status.PLANNED",
  ACTIVE: "organization.academicYears.status.ACTIVE",
  CLOSED: "organization.academicYears.status.CLOSED",
};

export interface AcademicYearFormProps {
  readonly mode: "create" | "edit";
  readonly values: YearValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<YearField, string>>>;
  readonly form: ResourceFormView;
  readonly onChange: (field: YearField, value: string) => void;
  readonly onBlur: (field: YearField) => void;
  readonly onClose: () => void;
}

/**
 * The form of an academic year. A new year starts PLANNED or in progress; an existing one can take any stage, and
 * the server refuses to reopen a closed one. The state of the record is only offered when editing.
 */
export function AcademicYearForm({ mode, values, fieldErrors, form, onChange, onBlur, onClose }: AcademicYearFormProps) {
  const { t } = useAdminText();
  const stages = mode === "create" ? (["PLANNED", "ACTIVE"] as const) : (["PLANNED", "ACTIVE", "CLOSED"] as const);
  const text = (field: YearField) => ({
    controlId: YEAR_CONTROL_IDS[field],
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
      title={t(mode === "create" ? "organization.academicYears.createTitle" : "organization.academicYears.editTitle")}
      description={t(mode === "create" ? "organization.academicYears.createDescription" : "organization.academicYears.editDescription")}
      submitLabel={t("organization.common.save")}
      form={form}
      onClose={onClose}
    >
      <TextField label={t("organization.academicYears.fields.name")} required {...text("name")} />
      <TextField label={t("organization.academicYears.fields.startDate")} kind="date" required {...text("startDate")} />
      <TextField label={t("organization.academicYears.fields.endDate")} kind="date" required {...text("endDate")} />
      <OptionField
        label={t("organization.academicYears.fields.status")}
        required
        options={stages.map((stage) => ({ value: stage, label: t(STATUS_KEYS[stage]) }))}
        {...text("status")}
      />
      {mode === "edit" ? (
        <OptionField
          label={t("organization.academicYears.fields.isActive")}
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
