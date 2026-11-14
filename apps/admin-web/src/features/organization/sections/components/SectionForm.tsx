import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { OptionField, ReadOnlyField, TextField, type Option } from "../../components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../components/ResourceFormDialog.tsx";
import { SECTION_CONTROL_IDS, type SectionField, type SectionValues } from "../section-form.ts";

export interface SectionFormProps {
  readonly mode: "create" | "edit";
  readonly values: SectionValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<SectionField, string>>>;
  /** The years that are not closed and the active grades, to choose from for a new section. */
  readonly yearOptions: readonly Option[];
  readonly gradeOptions: readonly Option[];
  /** The names of the year and the grade of the section being edited, shown and not editable. */
  readonly yearName: string;
  readonly gradeName: string;
  readonly form: ResourceFormView;
  readonly onChange: (field: SectionField, value: string) => void;
  readonly onBlur: (field: SectionField) => void;
  readonly onClose: () => void;
}

/** The form of a section: year and grade (chosen when new, fixed afterwards), a name, an optional capacity and, when editing, its state. */
export function SectionForm({
  mode,
  values,
  fieldErrors,
  yearOptions,
  gradeOptions,
  yearName,
  gradeName,
  form,
  onChange,
  onBlur,
  onClose,
}: SectionFormProps) {
  const { t } = useAdminText();
  const text = (field: SectionField) => ({
    controlId: SECTION_CONTROL_IDS[field],
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
      title={t(mode === "create" ? "organization.sections.createTitle" : "organization.sections.editTitle")}
      description={t(mode === "create" ? "organization.sections.createDescription" : "organization.sections.editDescription")}
      submitLabel={t("organization.common.save")}
      form={form}
      onClose={onClose}
    >
      {mode === "create" ? (
        <>
          <OptionField
            label={t("organization.sections.fields.academicYearId")}
            required
            options={yearOptions}
            placeholder={t("organization.common.chooseOption")}
            {...text("academicYearId")}
          />
          <OptionField
            label={t("organization.sections.fields.gradeId")}
            required
            options={gradeOptions}
            placeholder={t("organization.common.chooseOption")}
            {...text("gradeId")}
          />
        </>
      ) : (
        <>
          <ReadOnlyField label={t("organization.sections.fields.academicYearId")} controlId={SECTION_CONTROL_IDS.academicYearId} value={yearName} />
          <ReadOnlyField label={t("organization.sections.fields.gradeId")} controlId={SECTION_CONTROL_IDS.gradeId} value={gradeName} />
        </>
      )}
      <TextField label={t("organization.sections.fields.name")} required {...text("name")} />
      <TextField
        label={t("organization.sections.fields.capacity")}
        kind="numeric"
        description={t("organization.sections.capacityHelp")}
        {...text("capacity")}
      />
      {mode === "edit" ? (
        <OptionField
          label={t("organization.sections.fields.isActive")}
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
