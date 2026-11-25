import { useAdminText } from "../../i18n/use-admin-text.ts";
import { useConceptLookup } from "../../shared/catalog-lookups/use-concept-lookup.ts";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import type { Option } from "../../shared/resource-screen/components/fields.tsx";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { ScholarshipForm } from "./components/ScholarshipForm.tsx";
import {
  initialScholarshipValues,
  saveOf,
  SCHOLARSHIP_CONTROL_IDS,
  validateScholarship,
  type Scholarship,
  type ScholarshipField,
  type ScholarshipSave,
} from "./scholarship-form.ts";

export interface ScholarshipFormContainerProps {
  /** Absent when the scholarship is new. */
  readonly row?: Scholarship;
  /** Rejects with the failure of the request; resolves when the scholarship is saved. */
  readonly save: (save: ScholarshipSave) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a scholarship: validate before sending, send once, and turn a failure into what the form shows. */
export function ScholarshipFormContainer({ row, save, onClose }: ScholarshipFormContainerProps) {
  const { t } = useAdminText();
  const concepts = useConceptLookup();
  const form = useAccessForm<ScholarshipField>({
    fields: fieldSpecs(SCHOLARSHIP_CONTROL_IDS, initialScholarshipValues(row), ["code", "name"]),
    validate: validateScholarship(row),
    submit: async (values) => {
      try {
        await save(saveOf(row, values));
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "resource.saving",
  });

  // The active concepts, and the ones the scholarship already covers: one may have been deactivated since, and the list must still show what the
  // scholarship has.
  const covered = new Set(row?.concepts.map((concept) => concept.id));
  const conceptOptions: Option[] = concepts.rows
    .filter((concept) => concept.isActive || covered.has(concept.id))
    .map((concept) => ({ value: concept.id, label: t("scholarships.form.conceptOption", { code: concept.code, name: concept.name }) }));

  return (
    <ScholarshipForm
      mode={row === undefined ? "create" : "edit"}
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      conceptOptions={conceptOptions}
      conceptLookupFailure={concepts.failed ? { onRetry: concepts.retry } : undefined}
      noConcepts={!concepts.loading && !concepts.failed && conceptOptions.length === 0}
      benefitLocked={row?.benefitLocked ?? false}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
