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
import { AwardRequestContainer } from "./AwardRequestContainer.tsx";
import { AwardsView } from "./components/AwardsView.tsx";
import { awardPath } from "./scholarships-paths.ts";
import { AWARD_STATUSES, type AwardsSearch } from "./scholarships-search.ts";
import { scholarshipOptionOf } from "./scholarships-types.ts";
import { useAwardList } from "./use-award-queries.ts";
import { useScholarshipLookup } from "./use-scholarship-queries.ts";

export interface AwardsContainerProps {
  readonly search: AwardsSearch;
  readonly onSearchChange: SearchChange<AwardsSearch>;
}

/** The student chosen in this visit: the address only holds the id, so the name is remembered while it is the same student. */
interface NamedStudent {
  readonly id: string;
  readonly label: string;
}

/**
 * The awards. The status, the scholarship, the student, the order and the page are in the address, so a link (from the students registry) opens the
 * list already pointed at one student and the view can be shared; the server pages, filters and orders, and the browser never holds more than the
 * page it shows. The request of a new award opens a dialog, only for a session with `scholarships:create`.
 */
export function AwardsContainer({ search, onSearchChange }: AwardsContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const { can } = usePermissions();
  const list = useAwardList(toApiParams(search));
  const scholarships = useScholarshipLookup(false);
  const box = useDebouncedSearch();
  const found = useStudentSearch(box.text);
  const [named, setNamed] = useState<NamedStudent>();
  const view = useResourceView<never>();
  const mayRequest = can("scholarships:create");
  const filtered = search.status !== undefined || search.scholarshipId !== undefined || search.studentId !== undefined;
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered,
    cleared: { ...search, page: 1, status: undefined, scholarshipId: undefined, studentId: undefined },
    onCreate: mayRequest ? view.openCreate : undefined,
    loadFailedKey: "scholarships.awards.loadFailed",
    emptyKeys: { title: "scholarships.awards.emptyTitle", description: "scholarships.awards.emptyDescription", action: "scholarships.awards.emptyAction" },
    defaultSort: { columnId: "requestedAt", direction: "desc" },
  });

  const change = (next: Partial<AwardsSearch>) => {
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
        : (list.data?.items[0]?.studentCode ?? t("scholarships.awards.studentUnnamed"));

  return (
    <>
      <AwardsView
        filters={{
          status: {
            value: search.status ?? "",
            onChange: (status) => {
              change({ status: AWARD_STATUSES.find((known) => known === status) });
            },
          },
          scholarship: {
            value: search.scholarshipId ?? "",
            options: scholarships.rows.map((row) => ({ value: row.id, label: scholarshipOptionOf(row) })),
            onChange: (scholarshipId) => {
              change({ scholarshipId: scholarships.rows.find((row) => row.id === scholarshipId)?.id });
            },
          },
          student: {
            picker: {
              controlId: "awards-student",
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
        lookupFailure={scholarships.failed ? { onRetry: scholarships.retry } : undefined}
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        hrefOf={(row) => awardPath(row.id)}
        onOpen={(row) => void navigate({ to: awardPath(row.id) })}
        onRequest={mayRequest ? view.openCreate : undefined}
      />
      {view.dialog.kind === "closed" ? null : (
        <AwardRequestContainer
          studentId={search.studentId}
          studentLabel={chosenLabel}
          onClose={view.close}
          onRequested={(award) => {
            view.announce(t("scholarships.awards.requestedNotice", { student: award.studentCode ?? t("scholarships.awards.noValue") }));
            view.close();
          }}
        />
      )}
    </>
  );
}
