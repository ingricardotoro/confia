import { listModalities } from "../../../shared/api/organization-operations.ts";
import { toApiParams, type ModalitiesSearch } from "../list-search.ts";
import type { SearchChange } from "../search-change.ts";
import { useListState } from "../use-list-state.ts";
import { useResourceList, useResourceWrite } from "../use-organization-queries.ts";
import { useResourceView } from "../use-resource-view.ts";
import { ModalitiesView } from "./components/ModalitiesView.tsx";
import { updateBodyOf, writeModality, type Modality, type ModalitySave } from "./modality-form.ts";
import { ModalityFormContainer } from "./ModalityFormContainer.tsx";

export interface ModalitiesContainerProps {
  readonly search: ModalitiesSearch;
  readonly onSearchChange: SearchChange<ModalitiesSearch>;
}

/** The modalities: the list as the server serves it, the form in a dialog, and activate or deactivate. */
export function ModalitiesContainer({ search, onSearchChange }: ModalitiesContainerProps) {
  const list = useResourceList("modalities", toApiParams(search), listModalities);
  const write = useResourceWrite<ModalitySave, Modality>(writeModality);
  const view = useResourceView<Modality>();
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: false,
    cleared: { ...search, page: 1 },
    onCreate: view.openCreate,
    loadFailedKey: "organization.modalities.loadFailed",
    emptyKeys: {
      title: "organization.modalities.emptyTitle",
      description: "organization.modalities.emptyDescription",
      action: "organization.modalities.emptyAction",
    },
  });

  return (
    <>
      <ModalitiesView
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
        <ModalityFormContainer
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
