import type { TableSort } from "@confia/ui";
import { toApiParams } from "../organization/list-search.ts";
import type { SearchChange } from "../organization/search-change.ts";
import { useListState } from "../organization/use-list-state.ts";
import { useResourceView } from "../organization/use-resource-view.ts";
import { StudentsView } from "./components/StudentsView.tsx";
import { StudentFormContainer } from "./StudentFormContainer.tsx";
import { nameOf, toggleBodyOf, type Student } from "./student-form.ts";
import type { StudentsSearch } from "./students-search.ts";
import { useSearchBox } from "./use-search-box.ts";
import { useStudentList, useStudentWrite } from "./use-students-queries.ts";

export interface StudentsContainerProps {
  readonly search: StudentsSearch;
  readonly onSearchChange: SearchChange<StudentsSearch>;
}

/** The order the server applies when the address names none: by last name, ascending. */
const DEFAULT_SORT: TableSort = { columnId: "lastName", direction: "asc" };

/**
 * The students: the list as the server serves it (searched, filtered, ordered and paged there), the form in a dialog,
 * and activate or deactivate from the row. The state of the table is the address's: the search box only follows it.
 */
export function StudentsContainer({ search, onSearchChange }: StudentsContainerProps) {
  const list = useStudentList(toApiParams(search));
  const write = useStudentWrite();
  const view = useResourceView<Student>();
  const box = useSearchBox(search.q, (q) => {
    onSearchChange({ ...search, q, page: 1 }, { replace: true });
  });
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.q !== undefined || search.status !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, q: undefined, status: undefined },
    onCreate: view.openCreate,
    loadFailedKey: "students.loadFailed",
    emptyKeys: { title: "students.emptyTitle", description: "students.emptyDescription", action: "students.emptyAction" },
    defaultSort: DEFAULT_SORT,
  });

  return (
    <>
      <StudentsView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        togglingId={view.togglingId}
        filters={{
          search: { value: box.draft, onChange: box.onDraftChange, tooShort: box.tooShort },
          status: {
            value: search.status ?? "",
            onChange: (status) => {
              onSearchChange({ ...search, status: status === "" ? undefined : status, page: 1 }, { replace: true });
            },
          },
        }}
        onCreate={view.openCreate}
        onEdit={view.openEdit}
        onToggle={(row) => {
          const next = row.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
          void view.toggle(row.id, nameOf(row), next === "ACTIVE", () => write({ mode: "update", id: row.id, body: toggleBodyOf(row, next) }));
        }}
      />
      {view.dialog.kind === "closed" ? null : (
        <StudentFormContainer
          // A new form for each record, so nothing typed for one is shown for another.
          key={view.dialog.kind === "edit" ? view.dialog.row.id : "create"}
          {...(view.dialog.kind === "edit" ? { row: view.dialog.row } : {})}
          save={async (save) => {
            const saved = await write(save);
            view.saved(save.mode === "create" ? "created" : "updated", nameOf(saved));
          }}
          onClose={view.close}
        />
      )}
    </>
  );
}
