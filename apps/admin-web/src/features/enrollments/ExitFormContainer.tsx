import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { failureOf } from "../organization/organization-failure.ts";
import { fieldSpecs } from "../organization/organization-form.ts";
import { ExitForm } from "./components/ExitForm.tsx";
import { EXIT_CONTROL_IDS, exitBodyOf, initialExitValues, validateExit, type ExitField } from "./enrollment-forms.ts";
import { studentNameOf, type Enrollment } from "./enrollment-model.ts";
import type { EnrollmentWrite } from "./use-enrollments-queries.ts";

export interface ExitFormContainerProps {
  readonly row: Enrollment;
  /** Rejects with the failure of the request; resolves when the exit is registered. */
  readonly save: (write: Extract<EnrollmentWrite, { kind: "exit" }>) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the exit form: validate (the reason is required except for a graduation), send once and report. */
export function ExitFormContainer({ row, save, onClose }: ExitFormContainerProps) {
  const form = useAccessForm<ExitField>({
    fields: fieldSpecs(EXIT_CONTROL_IDS, initialExitValues(), ["withdrawalReason"]),
    validate: validateExit,
    submit: async (values) => {
      try {
        await save({ kind: "exit", id: row.id, body: exitBodyOf(values) });
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "enrollments.exit.saving",
  });

  return (
    <ExitForm
      studentName={studentNameOf(row)}
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
