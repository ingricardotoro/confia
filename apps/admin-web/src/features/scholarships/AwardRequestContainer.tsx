import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { useYearLookup } from "../../shared/organization-lookups/use-lookups.ts";
import { useDebouncedSearch } from "../../shared/resource-screen/use-debounced-search.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { useStudentSearch } from "../../shared/student-lookups/use-student-search.ts";
import type { StatementStudentSearchState } from "../../shared/student-lookups/components/StatementStudentPicker.tsx";
import { AwardRequestForm } from "./components/AwardRequestForm.tsx";
import { AWARD_CONTROL_IDS, initialAwardValues, requestBodyOf, validateAward, type AwardField } from "./award-model.ts";
import { awardFailureOf } from "./scholarships-failure.ts";
import { scholarshipOptionOf, type Award } from "./scholarships-types.ts";
import { useAwardRequest } from "./use-award-queries.ts";
import { useScholarshipLookup } from "./use-scholarship-queries.ts";

export interface AwardRequestContainerProps {
  /** The student the list is already pointed at, when it is: the request starts with them chosen. */
  readonly studentId?: string | undefined;
  /** How that student is named (the address only holds the id). */
  readonly studentLabel?: string | undefined;
  readonly onClose: () => void;
  /** The server accepted the request: the award exists, pending, and the lists are already read again. */
  readonly onRequested: (award: Award) => void;
}

/**
 * The behavior of the request of an award: validate before sending, send once, and turn a refusal of the server into what the form shows (an
 * inactive scholarship, a validity outside the year, an unknown student). Requesting books nothing and needs no `Idempotency-Key`: a repeated request
 * is another pending award, which the person sees in the list.
 */
export function AwardRequestContainer({ studentId, studentLabel, onClose, onRequested }: AwardRequestContainerProps) {
  const { t } = useAdminText();
  const requesting = useAwardRequest();
  const scholarships = useScholarshipLookup(true);
  const years = useYearLookup();
  const box = useDebouncedSearch();
  const found = useStudentSearch(box.text);
  const [named, setNamed] = useState<{ readonly id: string; readonly label: string } | undefined>(
    studentId === undefined || studentLabel === undefined ? undefined : { id: studentId, label: studentLabel },
  );

  const form = useAccessForm<AwardField>({
    fields: fieldSpecs(AWARD_CONTROL_IDS, initialAwardValues(studentId), []),
    validate: validateAward,
    submit: async (values) => {
      try {
        const award = await requesting.mutateAsync(requestBodyOf(values));
        onRequested(award);
        return undefined;
      } catch (error) {
        return awardFailureOf(error).failure;
      }
    },
    waitingKey: "scholarships.request.sending",
  });

  const searchState: StatementStudentSearchState =
    box.waiting ? "waiting" : found.isFetching ? "loading" : found.isError ? "failed" : found.data !== undefined && box.text !== undefined ? "ready" : "idle";
  const chosenId = form.values.studentId;
  const lookups = [scholarships, years];

  return (
    <AwardRequestForm
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      student={{
        picker: {
          controlId: AWARD_CONTROL_IDS.studentId,
          draft: box.draft,
          onDraftChange: box.setDraft,
          tooShort: box.tooShort,
          search: searchState,
          results: found.data?.items ?? [],
          onRetry: () => void found.refetch(),
          chosen: undefined,
          chosenPending: false,
          onChoose: (student) => {
            box.setDraft("");
            setNamed({ id: student.id, label: t("charges.statement.picker.optionText", { name: `${student.lastName}, ${student.firstName}`, code: student.studentCode }) });
            form.onChange("studentId", student.id);
          },
          onClear: () => undefined,
        },
        chosen:
          chosenId === ""
            ? undefined
            : {
                label: named?.id === chosenId ? named.label : t("scholarships.awards.studentUnnamed"),
                onClear: () => {
                  form.onChange("studentId", "");
                },
              },
      }}
      scholarshipOptions={scholarships.rows.map((row) => ({ value: row.id, label: scholarshipOptionOf(row) }))}
      yearOptions={years.rows.map((year) => ({ value: year.id, label: year.name }))}
      lookupFailure={
        lookups.some((lookup) => lookup.failed)
          ? {
              onRetry: () => {
                for (const lookup of lookups) if (lookup.failed) lookup.retry();
              },
            }
          : undefined
      }
      noScholarships={!scholarships.loading && !scholarships.failed && scholarships.rows.length === 0}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
