import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { failureOf } from "../organization/organization-failure.ts";
import { fieldSpecs } from "../organization/organization-form.ts";
import { StudentForm } from "./components/StudentForm.tsx";
import {
  STUDENT_CONTROL_IDS,
  TRIMMED_FIELDS,
  initialStudentValues,
  saveOf,
  validateStudent,
  type Student,
  type StudentField,
  type StudentSave,
} from "./student-form.ts";

export interface StudentFormContainerProps {
  /** Absent when the student is new. */
  readonly row?: Student;
  /** Rejects with the failure of the request; resolves when the student is saved. */
  readonly save: (save: StudentSave) => Promise<void>;
  readonly onClose: () => void;
}

/**
 * The behavior of the form of a student: validate before sending, send once, and turn a failure into what the form
 * shows. The form holds the typed national id only while it is mounted: the dialog unmounts it when it closes, after
 * a save or a cancel, and nothing outside the form ever gets the value (not the address, not a query key, not a log).
 */
export function StudentFormContainer({ row, save, onClose }: StudentFormContainerProps) {
  const form = useAccessForm<StudentField>({
    fields: fieldSpecs(STUDENT_CONTROL_IDS, initialStudentValues(row), TRIMMED_FIELDS),
    validate: validateStudent(row),
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
    <StudentForm
      mode={row === undefined ? "create" : "edit"}
      values={form.values}
      fieldErrors={form.fieldErrors}
      {...(row === undefined ? {} : { row })}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
