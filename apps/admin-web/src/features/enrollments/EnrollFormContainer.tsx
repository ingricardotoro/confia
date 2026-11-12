import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { failureOf } from "../organization/organization-failure.ts";
import { fieldSpecs } from "../organization/organization-form.ts";
import { EnrollForm, type SectionOptionsState } from "./components/EnrollForm.tsx";
import type { StudentSearchState } from "./components/StudentPicker.tsx";
import {
  ENROLL_CONTROL_IDS,
  enrollBodyOf,
  initialEnrollValues,
  validateEnroll,
  type EnrollField,
} from "./enrollment-forms.ts";
import { groupSections, type StudentChoice } from "./enrollment-model.ts";
import type { EnrollmentWrite } from "./use-enrollments-queries.ts";
import { useStudentSearch, useYearSections } from "./use-enrollments-queries.ts";
import { useDebouncedSearch } from "./use-debounced-search.ts";

export interface EnrollFormContainerProps {
  /** The year in progress, whose sections are offered; absent when there is none (or it is still loading). */
  readonly yearId: string | undefined;
  /** The years are still loading, so "no year in progress" is not yet known. */
  readonly yearLoading: boolean;
  readonly gradeLabels: ReadonlyMap<string, string>;
  /** Rejects with the failure of the request; resolves when the student is enrolled. */
  readonly save: (write: Extract<EnrollmentWrite, { kind: "enroll" }>) => Promise<void>;
  readonly onClose: () => void;
}

/**
 * The behavior of the enroll form: the search of a student (a request only for two characters or more, after a pause),
 * the sections of the year in progress, and the usual validate before sending, send once and turn a failure into what the form shows.
 * The student chosen is kept here, the form holds only its id; names live in the DOM and nowhere else.
 */
export function EnrollFormContainer({ yearId, yearLoading, gradeLabels, save, onClose }: EnrollFormContainerProps) {
  const { t } = useAdminText();
  const [chosen, setChosen] = useState<StudentChoice>();
  const box = useDebouncedSearch();
  const found = useStudentSearch(box.text);
  const sections = useYearSections(yearId, yearId !== undefined);
  const form = useAccessForm<EnrollField>({
    fields: fieldSpecs(ENROLL_CONTROL_IDS, initialEnrollValues(), []),
    validate: validateEnroll,
    submit: async (values) => {
      try {
        await save({ kind: "enroll", body: enrollBodyOf(values) });
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "enrollments.enroll.saving",
  });

  const search: StudentSearchState = box.waiting
    ? "waiting"
    : box.text === undefined
      ? "idle"
      : found.isError
        ? "failed"
        : found.isPending
          ? "loading"
          : "ready";
  const sectionsState: SectionOptionsState =
    yearId === undefined ? (yearLoading ? "loading" : "no-year") : sections.isError ? "failed" : sections.isPending ? "loading" : "ready";

  return (
    <EnrollForm
      values={form.values}
      fieldErrors={form.fieldErrors}
      picker={{
        draft: box.draft,
        onDraftChange: box.setDraft,
        tooShort: box.tooShort,
        search,
        results: (found.data?.items ?? []).map(({ id, studentCode, firstName, lastName }) => ({ id, studentCode, firstName, lastName })),
        onRetry: () => void found.refetch(),
        chosen,
        onChoose: (student) => {
          setChosen(student);
          form.onChange("studentId", student.id);
        },
        onClear: () => {
          setChosen(undefined);
          form.onChange("studentId", "");
        },
      }}
      sectionsState={sectionsState}
      sectionGroups={groupSections(sections.data?.items ?? [], gradeLabels, t("organization.common.unknownReference"))}
      onRetrySections={() => void sections.refetch()}
      form={form}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
