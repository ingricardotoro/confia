import { Alert, Button, FormError } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, OptionField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { StatementStudentPicker, type StatementStudentPickerProps } from "../../../shared/student-lookups/components/StatementStudentPicker.tsx";
import { ASSIGNMENT_CONTROL_IDS, type AssignmentField, type AssignmentValues } from "../assignment-model.ts";

export interface AssignmentRequestFormProps {
  readonly values: AssignmentValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<AssignmentField, string>>>;
  readonly form: ResourceFormView;
  /** The student: the search box while none is chosen, and the chosen one (with how to change it) afterwards. */
  readonly student: {
    readonly picker: StatementStudentPickerProps;
    readonly chosen?: { readonly label: string; readonly onClear: () => void } | undefined;
  };
  /** The agreement discounts that are active: a new assignment needs one. */
  readonly discountOptions: readonly Option[];
  /** Set when the discounts failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The discounts loaded and no agreement is active: there is nothing to assign yet. */
  readonly noDiscounts: boolean;
  readonly onChange: (field: AssignmentField, value: string) => void;
  readonly onBlur: (field: AssignmentField) => void;
  readonly onClose: () => void;
}

/**
 * The request of an assignment: the student, the agreement discount and the validity (the first and the last day, both inside the validity of the
 * discount, which the server checks). The assignment is born pending and another person decides it, which the form says before it is sent.
 */
export function AssignmentRequestForm({ values, fieldErrors, form, student, discountOptions, lookupFailure, noDiscounts, onChange, onBlur, onClose }: AssignmentRequestFormProps) {
  const { t } = useAdminText();
  const text = (field: AssignmentField) => ({
    controlId: ASSIGNMENT_CONTROL_IDS[field],
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
      title={t("scholarships.assignments.form.title")}
      description={t("scholarships.assignments.form.description")}
      submitLabel={t("scholarships.assignments.form.submit")}
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
      {noDiscounts ? (
        <Alert variant="info">
          <p>{t("scholarships.assignments.form.noDiscounts")}</p>
        </Alert>
      ) : null}
      <OptionField
        label={t("scholarships.assignments.form.discount")}
        required
        description={t("scholarships.assignments.form.discountHelp")}
        options={discountOptions}
        placeholder={t("resource.chooseOption")}
        {...text("discountId")}
      />
      <FieldGrid>
        <TextField label={t("scholarships.assignments.form.validFrom")} required kind="date" {...text("validFrom")} />
        <TextField label={t("scholarships.assignments.form.validTo")} required kind="date" description={t("scholarships.assignments.form.validToHelp")} {...text("validTo")} />
      </FieldGrid>
      <Alert variant="info">
        <p>{t("scholarships.assignments.form.pendingNote")}</p>
      </Alert>
    </ResourceFormDialog>
  );
}
