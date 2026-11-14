import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { listGrades } from "../../../shared/api/organization-operations.ts";
import { toApiParams, type GradesSearch } from "../list-search.ts";
import type { SearchChange } from "../search-change.ts";
import { useListState } from "../use-list-state.ts";
import { nameMapOf, optionsOf, useModalityLookup } from "../use-lookups.ts";
import { useResourceList, useResourceWrite } from "../use-organization-queries.ts";
import { useResourceView } from "../use-resource-view.ts";
import { GradesView } from "./components/GradesView.tsx";
import { updateBodyOf, writeGrade, type Grade, type GradeSave } from "./grade-form.ts";
import { GradeFormContainer } from "./GradeFormContainer.tsx";

export interface GradesContainerProps {
  readonly search: GradesSearch;
  readonly onSearchChange: SearchChange<GradesSearch>;
}

/** The grades: the list as the server serves it (filtered by modality there), the form in a dialog, and activate or deactivate. */
export function GradesContainer({ search, onSearchChange }: GradesContainerProps) {
  const { t } = useAdminText();
  const list = useResourceList("grades", toApiParams(search), listGrades);
  const modalities = useModalityLookup();
  const write = useResourceWrite<GradeSave, Grade>(writeGrade);
  const view = useResourceView<Grade>();
  const modalityNames = nameMapOf(modalities.rows);
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.modalityId !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, modalityId: undefined },
    onCreate: view.openCreate,
    loadFailedKey: "organization.grades.loadFailed",
    emptyKeys: {
      title: "organization.grades.emptyTitle",
      description: "organization.grades.emptyDescription",
      action: "organization.grades.emptyAction",
    },
  });

  return (
    <>
      <GradesView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        togglingId={view.togglingId}
        modalityNames={modalityNames}
        modalityFilter={{
          value: search.modalityId ?? "",
          options: optionsOf(modalities.rows),
          onChange: (modalityId) => {
            onSearchChange({ ...search, modalityId: modalityId === "" ? undefined : modalityId, page: 1 }, { replace: true });
          },
        }}
        lookupFailure={modalities.failed ? { onRetry: modalities.retry } : undefined}
        onCreate={view.openCreate}
        onEdit={view.openEdit}
        onToggle={(row) => {
          void view.toggle(row.id, row.name, !row.isActive, () =>
            write.mutateAsync({ mode: "update", id: row.id, body: updateBodyOf(row, !row.isActive) }),
          );
        }}
      />
      {view.dialog.kind === "closed" ? null : (
        <GradeFormContainer
          // A new form for each record, so nothing typed for one is shown for another.
          key={view.dialog.kind === "edit" ? view.dialog.row.id : "create"}
          {...(view.dialog.kind === "edit" ? { row: view.dialog.row } : {})}
          {...(search.modalityId === undefined ? {} : { defaultModalityId: search.modalityId })}
          modalityOptions={optionsOf(modalities.rows.filter((modality) => modality.isActive))}
          modalityName={view.dialog.kind === "edit" ? (modalityNames.get(view.dialog.row.modalityId) ?? t("organization.common.unknownReference")) : ""}
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
