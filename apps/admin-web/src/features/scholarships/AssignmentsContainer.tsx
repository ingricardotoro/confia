import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useDebouncedSearch } from "../../shared/resource-screen/use-debounced-search.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { useResourceView } from "../../shared/resource-screen/use-resource-view.ts";
import { useStudentSearch } from "../../shared/student-lookups/use-student-search.ts";
import type { StatementStudentSearchState } from "../../shared/student-lookups/components/StatementStudentPicker.tsx";
import { AssignmentRequestContainer } from "./AssignmentRequestContainer.tsx";
import { AssignmentsView } from "./components/AssignmentsView.tsx";
import { discountOptionOf } from "./discounts-types.ts";
import type { AssignmentsSearch } from "./discounts-search.ts";
import { assignmentPath } from "./scholarships-paths.ts";
import { AWARD_STATUSES } from "./scholarships-search.ts";
import { useAssignmentList } from "./use-assignment-queries.ts";
import { useDiscountLookup } from "./use-discount-queries.ts";

export interface AssignmentsContainerProps {
  readonly search: AssignmentsSearch;
  readonly onSearchChange: SearchChange<AssignmentsSearch>;
}

/** The student chosen in this visit: the address only holds the id, so the name is remembered while it is the same student. */
interface NamedStudent {
  readonly id: string;
  readonly label: string;
}

/**
 * The agreement assignments. The status, the discount, the student, the order and the page are in the address, so the view can be shared; the server
 * pages, filters and orders, and the browser never holds more than the page it shows. The request of a new assignment opens a dialog, only for a
 * session with `scholarships:create`.
 */
export function AssignmentsContainer({ search, onSearchChange }: AssignmentsContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const { can } = usePermissions();
  const list = useAssignmentList(toApiParams(search));
  // The filter offers every discount of the institution (an assignment keeps pointing at one that was deactivated); the request only the active agreements.
  const discounts = useDiscountLookup(false);
  const box = useDebouncedSearch();
  const found = useStudentSearch(box.text);
  const [named, setNamed] = useState<NamedStudent>();
  const view = useResourceView<never>();
  const mayRequest = can("scholarships:create");
  const filtered = search.status !== undefined || search.discountId !== undefined || search.studentId !== undefined;
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered,
    cleared: { ...search, page: 1, status: undefined, discountId: undefined, studentId: undefined },
    onCreate: mayRequest ? view.openCreate : undefined,
    loadFailedKey: "scholarships.assignments.loadFailed",
    emptyKeys: {
      title: "scholarships.assignments.emptyTitle",
      description: "scholarships.assignments.emptyDescription",
      action: "scholarships.assignments.emptyAction",
    },
    defaultSort: { columnId: "requestedAt", direction: "desc" },
  });

  const change = (next: Partial<AssignmentsSearch>) => {
    onSearchChange({ ...search, page: 1, ...next }, { replace: true });
  };
  const searchState: StatementStudentSearchState =
    box.waiting ? "waiting" : found.isFetching ? "loading" : found.isError ? "failed" : found.data !== undefined && box.text !== undefined ? "ready" : "idle";
  // The name of the student of the filter: the one chosen in this visit, or the code the first row of the list carries (the address only holds the id).
  const chosenLabel =
    search.studentId === undefined
      ? undefined
      : named?.id === search.studentId
        ? named.label
        : (list.data?.items[0]?.studentCode ?? t("scholarships.assignments.studentUnnamed"));

  return (
    <>
      <AssignmentsView
        filters={{
          status: {
            value: search.status ?? "",
            onChange: (status) => {
              change({ status: AWARD_STATUSES.find((known) => known === status) });
            },
          },
          discount: {
            value: search.discountId ?? "",
            options: discounts.rows.map((row) => ({ value: row.id, label: discountOptionOf(row) })),
            onChange: (discountId) => {
              change({ discountId: discounts.rows.find((row) => row.id === discountId)?.id });
            },
          },
          student: {
            picker: {
              controlId: "assignments-student",
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
                change({ studentId: student.id });
              },
              onClear: () => undefined,
            },
            chosen:
              chosenLabel === undefined
                ? undefined
                : {
                    label: chosenLabel,
                    onClear: () => {
                      change({ studentId: undefined });
                    },
                  },
          },
        }}
        lookupFailure={discounts.failed ? { onRetry: discounts.retry } : undefined}
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        hrefOf={(row) => assignmentPath(row.id)}
        onOpen={(row) => void navigate({ to: assignmentPath(row.id) })}
        onRequest={mayRequest ? view.openCreate : undefined}
      />
      {view.dialog.kind === "closed" ? null : (
        <AssignmentRequestContainer
          studentId={search.studentId}
          studentLabel={chosenLabel}
          onClose={view.close}
          onRequested={(assignment) => {
            view.announce(t("scholarships.assignments.requestedNotice", { student: assignment.studentCode ?? t("scholarships.assignments.noValue") }));
            view.close();
          }}
        />
      )}
    </>
  );
}
