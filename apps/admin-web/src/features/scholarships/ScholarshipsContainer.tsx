import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { useResourceView } from "../../shared/resource-screen/use-resource-view.ts";
import { ScholarshipsView } from "./components/ScholarshipsView.tsx";
import { ScholarshipFormContainer } from "./ScholarshipFormContainer.tsx";
import { BENEFIT_TYPES, type ScholarshipsSearch } from "./scholarships-search.ts";
import type { Scholarship } from "./scholarships-types.ts";
import { useScholarshipList, useScholarshipSave, useScholarshipToggle } from "./use-scholarship-queries.ts";

export interface ScholarshipsContainerProps {
  readonly search: ScholarshipsSearch;
  readonly onSearchChange: SearchChange<ScholarshipsSearch>;
}

/**
 * The scholarships: the list as the server serves it (filtered, ordered and paged there), the form in a dialog, and activate or deactivate from
 * the row. There is no delete: a scholarship that is no longer offered is deactivated, and it stops applying to the charges to come.
 */
export function ScholarshipsContainer({ search, onSearchChange }: ScholarshipsContainerProps) {
  const { can } = usePermissions();
  const list = useScholarshipList(toApiParams(search));
  const save = useScholarshipSave();
  const toggle = useScholarshipToggle();
  const view = useResourceView<Scholarship>();
  const mayWrite = can("scholarships:create");
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.benefitType !== undefined || search.active !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, benefitType: undefined, active: undefined },
    onCreate: mayWrite ? view.openCreate : undefined,
    loadFailedKey: "scholarships.list.loadFailed",
    emptyKeys: { title: "scholarships.list.emptyTitle", description: "scholarships.list.emptyDescription", action: "scholarships.list.emptyAction" },
    defaultSort: { columnId: "code", direction: "asc" },
  });

  return (
    <>
      <ScholarshipsView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        togglingId={view.togglingId}
        filters={{
          benefitType: {
            value: search.benefitType ?? "",
            onChange: (benefitType) => {
              onSearchChange({ ...search, benefitType: BENEFIT_TYPES.find((known) => known === benefitType), page: 1 }, { replace: true });
            },
          },
          active: {
            value: search.active === undefined ? "" : String(search.active),
            onChange: (active) => {
              onSearchChange({ ...search, active: active === "" ? undefined : active === "true", page: 1 }, { replace: true });
            },
          },
        }}
        onCreate={mayWrite ? view.openCreate : undefined}
        onEdit={mayWrite ? view.openEdit : undefined}
        onToggle={
          mayWrite
            ? (row) => {
                void view.toggle(row.id, row.name, !row.active, () => toggle.mutateAsync({ id: row.id, active: !row.active }));
              }
            : undefined
        }
      />
      {view.dialog.kind === "closed" ? null : (
        <ScholarshipFormContainer
          // A new form for each record, so nothing typed for one is shown for another.
          key={view.dialog.kind === "edit" ? view.dialog.row.id : "create"}
          {...(view.dialog.kind === "edit" ? { row: view.dialog.row } : {})}
          save={async (next) => {
            const saved = await save.mutateAsync(next);
            view.saved(next.mode === "create" ? "created" : "updated", saved.name);
          }}
          onClose={view.close}
        />
      )}
    </>
  );
}
