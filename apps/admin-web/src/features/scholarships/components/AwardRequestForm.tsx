import { Alert, Button, FormError } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, OptionField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { StatementStudentPicker, type StatementStudentPickerProps } from "../../../shared/student-lookups/components/StatementStudentPicker.tsx";
import { AWARD_CONTROL_IDS, type AwardField, type AwardValues } from "../award-model.ts";

export interface AwardRequestFormProps {
  readonly values: AwardValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<AwardField, string>>>;
  readonly form: ResourceFormView;
  /** The student: the search box while none is chosen, and the chosen one (with how to change it) afterwards. */
  readonly student: {
    readonly picker: StatementStudentPickerProps;
    readonly chosen?: { readonly label: string; readonly onClear: () => void } | undefined;
  };
  /** The scholarships that are active: a new award needs one. */
  readonly scholarshipOptions: readonly Option[];
  readonly yearOptions: readonly Option[];
  /** Set when the scholarships or the years failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The scholarships loaded and none is active: there is nothing to award yet. */
  readonly noScholarships: boolean;
  readonly onChange: (field: AwardField, value: string) => void;
  readonly onBlur: (field: AwardField) => void;
  readonly onClose: () => void;
}

/**
 * The request of an award: the student, the scholarship, the academic year and the validity (the first and the last day, both inside the year, which the
 * server checks). The award is born pending and another person decides it, which the form says before it is sent.
 */
export function AwardRequestForm({ values, fieldErrors, form, student, scholarshipOptions, yearOptions, lookupFailure, noScholarships, onChange, onBlur, onClose }: AwardRequestFormProps) {
  const { t } = useAdminText();
  const text = (field: AwardField) => ({
    controlId: AWARD_CONTROL_IDS[field],
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
      title={t("scholarships.request.title")}
      description={t("scholarships.request.description")}
      submitLabel={t("scholarships.request.submit")}
      form={form}
      onClose={onClose}
    >
      {student.chosen === undefined ? (
        <div className="flex flex-col gap-1">
          <StatementStudentPicker {...student.picker} />
          {fieldErrors.studentId === undefined ? null : <FormError>{fieldErrors.studentId}</FormError>}
        </div>
      ) : (
        <div role="group" aria-label={t("charges.statement.picker.chosen")} className="flex flex-col gap-1.5">
          <p className="text-sm font-medium text-text-primary">{t("charges.statement.picker.label")}</p>
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border-subtle px-3 py-2">
            <p className="text-sm font-medium text-text-primary">{student.chosen.label}</p>
            <Button variant="secondary" size="sm" onClick={student.chosen.onClear}>
              {t("charges.statement.picker.change")}
            </Button>
          </div>
        </div>
      )}
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("resource.optionsFailed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      {noScholarships ? (
        <Alert variant="info">
          <p>{t("scholarships.request.noScholarships")}</p>
        </Alert>
      ) : null}
      <OptionField label={t("scholarships.request.scholarship")} required options={scholarshipOptions} placeholder={t("resource.chooseOption")} {...text("scholarshipId")} />
      <OptionField
        label={t("scholarships.request.academicYear")}
        required
        description={t("scholarships.request.academicYearHelp")}
        options={yearOptions}
        placeholder={t("resource.chooseOption")}
        {...text("academicYearId")}
      />
      <FieldGrid>
        <TextField label={t("scholarships.request.validFrom")} required kind="date" {...text("validFrom")} />
        <TextField label={t("scholarships.request.validTo")} required kind="date" description={t("scholarships.request.validToHelp")} {...text("validTo")} />
      </FieldGrid>
      <Alert variant="info">
        <p>{t("scholarships.request.pendingNote")}</p>
      </Alert>
    </ResourceFormDialog>
  );
}
