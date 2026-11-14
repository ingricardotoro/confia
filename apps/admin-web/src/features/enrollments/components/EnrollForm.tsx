import { Alert, Button } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { TextField } from "../../organization/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../organization/components/ResourceFormDialog.tsx";
import { ENROLL_CONTROL_IDS, type EnrollField, type EnrollValues } from "../enrollment-forms.ts";
import type { OptionGroup } from "../enrollment-model.ts";
import { SectionField } from "./SectionField.tsx";
import { StudentPicker, type StudentPickerProps } from "./StudentPicker.tsx";

/** What the form knows of the sections it offers: there is no year in progress, they are loading, they failed, or they are here. */
export type SectionOptionsState = "no-year" | "loading" | "failed" | "ready";

export interface EnrollFormProps {
  readonly values: EnrollValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<EnrollField, string>>>;
  readonly picker: Omit<StudentPickerProps, "controlId" | "error">;
  readonly sectionsState: SectionOptionsState;
  readonly sectionGroups: readonly OptionGroup[];
  readonly onRetrySections: () => void;
  readonly form: ResourceFormView;
  readonly onChange: (field: EnrollField, value: string) => void;
  readonly onBlur: (field: EnrollField) => void;
  readonly onClose: () => void;
}

/**
 * The form to enroll a student, in a dialog: find the student, choose a section of the year in progress (grouped by
 * modality and grade) and the day of enrollment, which starts on today. The year is the section's, so it is not asked.
 * The submit button is never disabled for being incomplete: it names what is missing.
 */
export function EnrollForm({ values, fieldErrors, picker, sectionsState, sectionGroups, onRetrySections, form, onChange, onBlur, onClose }: EnrollFormProps) {
  const { t } = useAdminText();
  const noSections = sectionsState === "ready" && sectionGroups.length === 0;
  return (
    <ResourceFormDialog
      title={t("enrollments.enroll.title")}
      description={t("enrollments.enroll.description")}
      submitLabel={t("enrollments.enroll.submit")}
      form={form}
      onClose={onClose}
    >
      <StudentPicker {...picker} controlId={ENROLL_CONTROL_IDS.studentId} error={fieldErrors.studentId} />
      {sectionsState === "no-year" ? <Alert variant="warning">{t("enrollments.enroll.noActiveYear")}</Alert> : null}
      {sectionsState === "failed" ? (
        <Alert variant="warning">
          <p>{t("enrollments.enroll.optionsFailed")}</p>
          <Button variant="secondary" size="sm" className="mt-2" onClick={onRetrySections}>
            {t("enrollments.enroll.retryOptions")}
          </Button>
        </Alert>
      ) : null}
      <SectionField
        label={t("enrollments.filters.section")}
        controlId={ENROLL_CONTROL_IDS.sectionId}
        required
        value={values.sectionId}
        groups={sectionGroups}
        description={t(
          sectionsState === "loading" ? "enrollments.enroll.loadingSections" : noSections ? "enrollments.enroll.noSections" : "enrollments.enroll.sectionHelp",
        )}
        error={fieldErrors.sectionId}
        onChange={(value) => {
          onChange("sectionId", value);
        }}
        onBlur={() => {
          onBlur("sectionId");
        }}
      />
      <TextField
        label={t("enrollments.enroll.date")}
        controlId={ENROLL_CONTROL_IDS.enrolledOn}
        kind="date"
        required
        value={values.enrolledOn}
        error={fieldErrors.enrolledOn}
        onChange={(value) => {
          onChange("enrolledOn", value);
        }}
        onBlur={() => {
          onBlur("enrolledOn");
        }}
      />
    </ResourceFormDialog>
  );
}
