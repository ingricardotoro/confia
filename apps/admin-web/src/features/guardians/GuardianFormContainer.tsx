import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { failureOf } from "../organization/organization-failure.ts";
import { fieldSpecs } from "../organization/organization-form.ts";
import type { AccessFailure } from "../../shared/forms/access-failure.ts";
import { GuardianForm } from "./components/GuardianForm.tsx";
import {
  GUARDIAN_CONTROL_IDS,
  TRIMMED_FIELDS,
  initialGuardianValues,
  saveOf,
  validateGuardian,
  type Guardian,
  type GuardianField,
  type GuardianSave,
} from "./guardian-form.ts";

export interface GuardianFormContainerProps {
  /** Absent when the guardian is new. */
  readonly row?: Guardian;
  /** Rejects with the failure of the request; resolves when the guardian is saved. */
  readonly save: (save: GuardianSave) => Promise<void>;
  readonly onClose: () => void;
}

/** The duplicate document of this screen is a guardian's: the shared text names the student. */
function guardianFailureOf(error: unknown): AccessFailure {
  const failure = failureOf(error);
  return failure.kind === "message" && failure.key === "problem.duplicateNationalId" ? { ...failure, key: "problem.duplicateGuardianNationalId" } : failure;
}

/**
 * The behavior of the form of a guardian: validate before sending, send once, and turn a failure into what the form
 * shows. The form holds the typed national id and RTN only while it is mounted: the dialog unmounts it when it closes,
 * after a save or a cancel, and nothing outside the form ever gets the values (not the address, not a query key, not a log).
 */
export function GuardianFormContainer({ row, save, onClose }: GuardianFormContainerProps) {
  const form = useAccessForm<GuardianField>({
    fields: fieldSpecs(GUARDIAN_CONTROL_IDS, initialGuardianValues(row), TRIMMED_FIELDS),
    validate: validateGuardian(row),
    submit: async (values) => {
      try {
        await save(saveOf(row, values));
        return undefined;
      } catch (error) {
        return guardianFailureOf(error);
      }
    },
    waitingKey: "organization.common.saving",
  });

  return (
    <GuardianForm
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
