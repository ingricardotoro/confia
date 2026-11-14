import { listAcademicYears } from "../../../shared/api/organization-operations.ts";
import { toApiParams, type AcademicYearsSearch } from "../list-search.ts";
import type { SearchChange } from "../search-change.ts";
import { useListState } from "../use-list-state.ts";
import { useResourceList, useResourceWrite } from "../use-organization-queries.ts";
import { useResourceView } from "../use-resource-view.ts";
import { updateBodyOf, writeYear, type AcademicYear, type YearSave } from "./academic-year-form.ts";
import { AcademicYearFormContainer } from "./AcademicYearFormContainer.tsx";
import { AcademicYearsView } from "./components/AcademicYearsView.tsx";

export interface AcademicYearsContainerProps {
  readonly search: AcademicYearsSearch;
  readonly onSearchChange: SearchChange<AcademicYearsSearch>;
}

/** The academic years: the list as the server serves it, the form in a dialog, and activate or deactivate. */
export function AcademicYearsContainer({ search, onSearchChange }: AcademicYearsContainerProps) {
  const list = useResourceList("academic-years", toApiParams(search), listAcademicYears);
  const write = useResourceWrite<YearSave, AcademicYear>(writeYear);
  const view = useResourceView<AcademicYear>();
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: false,
    cleared: { ...search, page: 1 },
    onCreate: view.openCreate,
    loadFailedKey: "organization.academicYears.loadFailed",
    emptyKeys: {
      title: "organization.academicYears.emptyTitle",
      description: "organization.academicYears.emptyDescription",
      action: "organization.academicYears.emptyAction",
    },
    // The order the server applies when the URL names none.
    defaultSort: { columnId: "startDate", direction: "desc" },
  });

  return (
    <>
      <AcademicYearsView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        togglingId={view.togglingId}
        onCreate={view.openCreate}
        onEdit={view.openEdit}
        onToggle={(row) => {
          void view.toggle(row.id, row.name, !row.isActive, () =>
            write.mutateAsync({ mode: "update", id: row.id, body: updateBodyOf(row, !row.isActive) }),
          );
        }}
      />
      {view.dialog.kind === "closed" ? null : (
        <AcademicYearFormContainer
          // A new form for each record, so nothing typed for one is shown for another.
          key={view.dialog.kind === "edit" ? view.dialog.row.id : "create"}
          {...(view.dialog.kind === "edit" ? { row: view.dialog.row } : {})}
          save={async (save) => {
            const saved = await write.mutateAsync(save);
            view.saved(save.mode === "create" ? "created" : "updated", saved.name);
          }}
          onClose={view.close}
        />
      )}
    </>
  );
}
