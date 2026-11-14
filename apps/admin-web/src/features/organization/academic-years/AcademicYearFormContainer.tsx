import { useAccessForm } from "../../../shared/forms/use-access-form.ts";
import { failureOf } from "../organization-failure.ts";
import { fieldSpecs } from "../organization-form.ts";
import { YEAR_CONTROL_IDS, initialYearValues, saveOf, validateYear, type AcademicYear, type YearField, type YearSave } from "./academic-year-form.ts";
import { AcademicYearForm } from "./components/AcademicYearForm.tsx";

export interface AcademicYearFormContainerProps {
  /** Absent when the year is new. */
  readonly row?: AcademicYear;
  /** Rejects with the failure of the request; resolves when the year is saved. */
  readonly save: (save: YearSave) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a year: validate before sending, send once, and turn a failure into what the form shows. */
export function AcademicYearFormContainer({ row, save, onClose }: AcademicYearFormContainerProps) {
  const form = useAccessForm<YearField>({
    fields: fieldSpecs(YEAR_CONTROL_IDS, initialYearValues(row), ["name"]),
    validate: validateYear(row),
    submit: async (values) => {
      try {
        await save(saveOf(row, values));
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "organization.common.saving",
  });

  return (
    <AcademicYearForm
      mode={row === undefined ? "create" : "edit"}
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
