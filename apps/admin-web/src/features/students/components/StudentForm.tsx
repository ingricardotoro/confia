import { Checkbox, FormField, Input, Label } from "@confia/ui";
import { useId } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { OptionField, ReadOnlyField, TextField } from "../../organization/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../organization/components/ResourceFormDialog.tsx";
import { STUDENT_CONTROL_IDS, type Student, type StudentField, type StudentValues } from "../student-form.ts";

export interface StudentFormProps {
  readonly mode: "create" | "edit";
  readonly values: StudentValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<StudentField, string>>>;
  /** The student being edited: what it shows of the stored document is the masked text it carries. */
  readonly row?: Pick<Student, "nationalIdMasked" | "hasNationalId">;
  readonly form: ResourceFormView;
  readonly onChange: (field: StudentField, value: string) => void;
  readonly onBlur: (field: StudentField) => void;
  readonly onClose: () => void;
}

/**
 * The form of a student, in a dialog. The code is optional when new (the system generates one) and fixed afterwards.
 * The document is handled in three separate acts when editing, so none happens by accident: the current value is
 * shown masked and cannot be edited, a separate box replaces it (empty keeps it), and only an explicit checkbox
 * removes it.
 */
export function StudentForm({ mode, values, fieldErrors, row, form, onChange, onBlur, onClose }: StudentFormProps) {
  const { t } = useAdminText();
  const removeHelpId = useId();
  const editing = mode === "edit";
  const removing = values.removeNationalId === "true";
  const text = (field: StudentField) => ({
    controlId: STUDENT_CONTROL_IDS[field],
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
      title={t(editing ? "students.editTitle" : "students.createTitle")}
      description={t(editing ? "students.editDescription" : "students.createDescription")}
      submitLabel={t("organization.common.save")}
      form={form}
      onClose={onClose}
    >
      {editing ? (
        <ReadOnlyField label={t("students.fields.studentCode")} controlId={STUDENT_CONTROL_IDS.studentCode} value={values.studentCode} />
      ) : (
        <TextField label={t("students.fields.studentCode")} description={t("students.codeHelp")} {...text("studentCode")} />
      )}
      <TextField label={t("students.fields.firstName")} required {...text("firstName")} />
      <TextField label={t("students.fields.lastName")} required {...text("lastName")} />
      <TextField label={t("students.fields.birthDate")} kind="date" required {...text("birthDate")} />
      <OptionField
        label={t("students.fields.sex")}
        required
        options={(["F", "M", "UNSPECIFIED"] as const).map((sex) => ({ value: sex, label: t(`students.sex.${sex}`) }))}
        placeholder={t("organization.common.chooseOption")}
        {...text("sex")}
      />
      {editing ? (
        <>
          <FormField label={t("students.currentDocument")} controlId="student-current-document">
            {(control) => <Input {...control} readOnly value={row?.nationalIdMasked ?? t("students.noDocumentText")} />}
          </FormField>
          <TextField
            label={t("students.replaceDocument")}
            description={t("students.replaceDocumentHelp")}
            disabled={removing}
            {...text("nationalId")}
          />
          {row?.hasNationalId === true ? (
            <div className="flex flex-col gap-1.5">
              <div className="flex items-center gap-2">
                <Checkbox
                  id={STUDENT_CONTROL_IDS.removeNationalId}
                  aria-describedby={removeHelpId}
                  checked={removing}
                  onCheckedChange={(checked) => {
                    onChange("removeNationalId", checked === true ? "true" : "false");
                    // What was typed to replace the document is dropped: removing and replacing are different acts.
                    if (checked === true) onChange("nationalId", "");
                  }}
                />
                <Label htmlFor={STUDENT_CONTROL_IDS.removeNationalId}>{t("students.removeDocument")}</Label>
              </div>
              <p id={removeHelpId} className="text-sm text-text-secondary">
                {t("students.removeDocumentHelp")}
              </p>
            </div>
          ) : null}
          <OptionField
            label={t("students.fields.status")}
            options={[
              { value: "ACTIVE", label: t("organization.common.active") },
              { value: "INACTIVE", label: t("organization.common.inactive") },
            ]}
            {...text("status")}
          />
        </>
      ) : (
        <TextField label={t("students.fields.nationalId")} description={t("students.nationalIdHelp")} {...text("nationalId")} />
      )}
    </ResourceFormDialog>
  );
}
