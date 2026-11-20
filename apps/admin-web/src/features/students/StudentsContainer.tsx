import type { TableSort } from "@confia/ui";
import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { StudentGuardiansContainer } from "../guardians/StudentGuardiansContainer.tsx";
import { STATEMENT_PATH, studentStatementPath } from "../../shared/navigation/statement-paths.ts";
import { STUDENT_CHARGES_PATH, studentChargesPath } from "../../shared/navigation/student-charges-paths.ts";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { useResourceView } from "../../shared/resource-screen/use-resource-view.ts";
import { StudentsView } from "./components/StudentsView.tsx";
import { StudentFormContainer } from "./StudentFormContainer.tsx";
import { nameOf, toggleBodyOf, type Student } from "./student-form.ts";
import type { StudentsSearch } from "./students-search.ts";
import { useSearchBox } from "../../shared/resource-screen/use-search-box.ts";
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
  const { can } = usePermissions();
  const navigate = useNavigate();
  const list = useStudentList(toApiParams(search));
  const write = useStudentWrite();
  const view = useResourceView<Student>();
  // The student whose guardians are being managed, if any: its own dialog, apart from the form.
  const [guardiansOf, setGuardiansOf] = useState<Student>();
  const box = useSearchBox(search.q, (q) => {
    onSearchChange({ ...search, q, page: 1 }, { replace: true });
  });
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.q !== undefined || search.status !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, q: undefined, status: undefined },
    onCreate: can("students:create") ? view.openCreate : undefined,
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
        onCreate={can("students:create") ? view.openCreate : undefined}
        onEdit={can("students:update") ? view.openEdit : undefined}
        statementHrefOf={(row) => studentStatementPath(row.id)}
        onStatement={(row) => void navigate({ to: STATEMENT_PATH, search: { studentId: row.id } })}
        chargesHrefOf={(row) => studentChargesPath(row.id)}
        onCharges={(row) => void navigate({ to: STUDENT_CHARGES_PATH, search: { studentId: row.id } })}
        onGuardians={
          can("guardians:update")
            ? (row) => {
                view.clearNotice();
                setGuardiansOf(row);
              }
            : undefined
        }
        onToggle={can("students:update") ? (row) => {
          const next = row.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
          void view.toggle(row.id, nameOf(row), next === "ACTIVE", () => write({ mode: "update", id: row.id, body: toggleBodyOf(row, next) }));
        } : undefined}
      />
      {guardiansOf === undefined ? null : (
        <StudentGuardiansContainer
          key={guardiansOf.id}
          studentId={guardiansOf.id}
          studentName={nameOf(guardiansOf)}
          onSaved={(message) => {
            setGuardiansOf(undefined);
            view.announce(message);
          }}
          onClose={() => {
            setGuardiansOf(undefined);
          }}
        />
      )}
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
