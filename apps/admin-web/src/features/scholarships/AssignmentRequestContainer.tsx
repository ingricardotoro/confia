import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import { useDebouncedSearch } from "../../shared/resource-screen/use-debounced-search.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { useStudentSearch } from "../../shared/student-lookups/use-student-search.ts";
import type { StatementStudentSearchState } from "../../shared/student-lookups/components/StatementStudentPicker.tsx";
import { AssignmentRequestForm } from "./components/AssignmentRequestForm.tsx";
import { ASSIGNMENT_CONTROL_IDS, assignmentRequestBodyOf, initialAssignmentValues, validateAssignment, type AssignmentField } from "./assignment-model.ts";
import { discountOptionOf, type DiscountAssignment } from "./discounts-types.ts";
import { assignmentFailureOf } from "./scholarships-failure.ts";
import { useAssignmentRequest } from "./use-assignment-queries.ts";
import { useDiscountLookup } from "./use-discount-queries.ts";

export interface AssignmentRequestContainerProps {
  /** The student the list is already pointed at, when it is: the request starts with them chosen. */
  readonly studentId?: string | undefined;
  /** How that student is named (the address only holds the id). */
  readonly studentLabel?: string | undefined;
  readonly onClose: () => void;
  /** The server accepted the request: the assignment exists, pending, and the lists are already read again. */
  readonly onRequested: (assignment: DiscountAssignment) => void;
}

/**
 * The behavior of the request of an assignment: validate before sending, send once, and turn a refusal of the server into what the form shows (an
 * inactive discount, a validity outside the one of the discount, an unknown student). Requesting books nothing and needs no `Idempotency-Key`: a
 * repeated request is another pending assignment, which the person sees in the list.
 */
export function AssignmentRequestContainer({ studentId, studentLabel, onClose, onRequested }: AssignmentRequestContainerProps) {
  const { t } = useAdminText();
  const requesting = useAssignmentRequest();
  const discounts = useDiscountLookup(true);
  const box = useDebouncedSearch();
  const found = useStudentSearch(box.text);
  const [named, setNamed] = useState<{ readonly id: string; readonly label: string } | undefined>(
    studentId === undefined || studentLabel === undefined ? undefined : { id: studentId, label: studentLabel },
  );

  const form = useAccessForm<AssignmentField>({
    fields: fieldSpecs(ASSIGNMENT_CONTROL_IDS, initialAssignmentValues(studentId), []),
    validate: validateAssignment,
    submit: async (values) => {
      try {
        const assignment = await requesting.mutateAsync(assignmentRequestBodyOf(values));
        onRequested(assignment);
        return undefined;
      } catch (error) {
        return assignmentFailureOf(error).failure;
      }
    },
    waitingKey: "scholarships.assignments.form.sending",
  });

  const searchState: StatementStudentSearchState =
    box.waiting ? "waiting" : found.isFetching ? "loading" : found.isError ? "failed" : found.data !== undefined && box.text !== undefined ? "ready" : "idle";
  const chosenId = form.values.studentId;

  return (
    <AssignmentRequestForm
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      student={{
        picker: {
          controlId: ASSIGNMENT_CONTROL_IDS.studentId,
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
                label: named?.id === chosenId ? named.label : t("scholarships.assignments.studentUnnamed"),
                onClear: () => {
                  form.onChange("studentId", "");
                },
              },
      }}
      discountOptions={discounts.rows.map((row) => ({ value: row.id, label: discountOptionOf(row) }))}
      lookupFailure={discounts.failed ? { onRetry: discounts.retry } : undefined}
      noDiscounts={!discounts.loading && !discounts.failed && discounts.rows.length === 0}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
