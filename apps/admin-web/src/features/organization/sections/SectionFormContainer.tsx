import { useAccessForm } from "../../../shared/forms/use-access-form.ts";
import type { Option } from "../components/fields.tsx";
import { failureOf } from "../organization-failure.ts";
import { fieldSpecs } from "../organization-form.ts";
import { SectionForm } from "./components/SectionForm.tsx";
import { SECTION_CONTROL_IDS, initialSectionValues, saveOf, validateSection, type Section, type SectionField, type SectionSave } from "./section-form.ts";

export interface SectionFormContainerProps {
  /** Absent when the section is new. */
  readonly row?: Section;
  /** The filters of the list, offered first to a new section. */
  readonly defaults: { readonly academicYearId?: string; readonly gradeId?: string };
  readonly yearOptions: readonly Option[];
  readonly gradeOptions: readonly Option[];
  readonly yearName: string;
  readonly gradeName: string;
  /** Rejects with the failure of the request; resolves when the section is saved. */
  readonly save: (save: SectionSave) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a section: validate before sending, send once, and turn a failure into what the form shows. */
export function SectionFormContainer({ row, defaults, yearOptions, gradeOptions, yearName, gradeName, save, onClose }: SectionFormContainerProps) {
  const form = useAccessForm<SectionField>({
    fields: fieldSpecs(SECTION_CONTROL_IDS, initialSectionValues(row, defaults), ["name", "capacity"]),
    validate: validateSection(row),
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
    <SectionForm
      mode={row === undefined ? "create" : "edit"}
      values={form.values}
      fieldErrors={form.fieldErrors}
      yearOptions={yearOptions}
      gradeOptions={gradeOptions}
      yearName={yearName}
      gradeName={gradeName}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
