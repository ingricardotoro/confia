import { useAccessForm } from "../../../shared/forms/use-access-form.ts";
import type { Option } from "../components/fields.tsx";
import { failureOf } from "../organization-failure.ts";
import { fieldSpecs } from "../organization-form.ts";
import { GradeForm } from "./components/GradeForm.tsx";
import { GRADE_CONTROL_IDS, initialGradeValues, saveOf, validateGrade, type Grade, type GradeField, type GradeSave } from "./grade-form.ts";

export interface GradeFormContainerProps {
  /** Absent when the grade is new. */
  readonly row?: Grade;
  /** The modality the list is filtered by, offered first to a new grade. */
  readonly defaultModalityId?: string;
  readonly modalityOptions: readonly Option[];
  readonly modalityName: string;
  /** Rejects with the failure of the request; resolves when the grade is saved. */
  readonly save: (save: GradeSave) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a grade: validate before sending, send once, and turn a failure into what the form shows. */
export function GradeFormContainer({ row, defaultModalityId, modalityOptions, modalityName, save, onClose }: GradeFormContainerProps) {
  const form = useAccessForm<GradeField>({
    fields: fieldSpecs(GRADE_CONTROL_IDS, initialGradeValues(row, defaultModalityId), ["name", "ordinal"]),
    validate: validateGrade(row),
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
    <GradeForm
      mode={row === undefined ? "create" : "edit"}
      values={form.values}
      fieldErrors={form.fieldErrors}
      modalityOptions={modalityOptions}
      modalityName={modalityName}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
