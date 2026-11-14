import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { listSections } from "../../../shared/api/organization-operations.ts";
import { toApiParams, type SectionsSearch } from "../list-search.ts";
import type { SearchChange } from "../search-change.ts";
import { useListState } from "../use-list-state.ts";
import { nameMapOf, optionsOf, useGradeLookup, useModalityLookup, useYearLookup } from "../use-lookups.ts";
import { useResourceList, useResourceWrite } from "../use-organization-queries.ts";
import { useResourceView } from "../use-resource-view.ts";
import { SectionsView } from "./components/SectionsView.tsx";
import { SectionFormContainer } from "./SectionFormContainer.tsx";
import { updateBodyOf, writeSection, type Section, type SectionSave } from "./section-form.ts";

export interface SectionsContainerProps {
  readonly search: SectionsSearch;
  readonly onSearchChange: SearchChange<SectionsSearch>;
}

/** The sections: the list as the server serves it (filtered by year and grade there), the form in a dialog, and activate or deactivate. */
export function SectionsContainer({ search, onSearchChange }: SectionsContainerProps) {
  const { t } = useAdminText();
  const list = useResourceList("sections", toApiParams(search), listSections);
  const years = useYearLookup();
  const grades = useGradeLookup();
  const modalities = useModalityLookup();
  const write = useResourceWrite<SectionSave, Section>(writeSection);
  const view = useResourceView<Section>();

  const yearNames = nameMapOf(years.rows);
  const modalityNames = nameMapOf(modalities.rows);
  // A grade is told with its modality, because the same name can exist in two modalities.
  const gradeLabels = grades.rows.map((grade) => {
    const modality = modalityNames.get(grade.modalityId);
    return { id: grade.id, name: modality === undefined ? grade.name : `${modality} · ${grade.name}`, isActive: grade.isActive };
  });
  const gradeNames = nameMapOf(gradeLabels);
  const unknown = t("organization.common.unknownReference");

  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.academicYearId !== undefined || search.gradeId !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, academicYearId: undefined, gradeId: undefined },
    onCreate: view.openCreate,
    loadFailedKey: "organization.sections.loadFailed",
    emptyKeys: {
      title: "organization.sections.emptyTitle",
      description: "organization.sections.emptyDescription",
      action: "organization.sections.emptyAction",
    },
  });
  const lookupsFailed = years.failed || grades.failed || modalities.failed;

  return (
    <>
      <SectionsView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        togglingId={view.togglingId}
        yearNames={yearNames}
        gradeNames={gradeNames}
        yearFilter={{
          value: search.academicYearId ?? "",
          options: optionsOf(years.rows),
          onChange: (id) => {
            onSearchChange({ ...search, academicYearId: id === "" ? undefined : id, page: 1 }, { replace: true });
          },
        }}
        gradeFilter={{
          value: search.gradeId ?? "",
          options: optionsOf(gradeLabels),
          onChange: (id) => {
            onSearchChange({ ...search, gradeId: id === "" ? undefined : id, page: 1 }, { replace: true });
          },
        }}
        lookupFailure={
          lookupsFailed
            ? {
                onRetry: () => {
                  for (const lookup of [years, grades, modalities]) if (lookup.failed) lookup.retry();
                },
              }
            : undefined
        }
        onCreate={view.openCreate}
        onEdit={view.openEdit}
        onToggle={(row) => {
          void view.toggle(row.id, row.name, !row.isActive, () =>
            write.mutateAsync({ mode: "update", id: row.id, body: updateBodyOf(row, !row.isActive) }),
          );
        }}
      />
      {view.dialog.kind === "closed" ? null : (
        <SectionFormContainer
          // A new form for each record, so nothing typed for one is shown for another.
          key={view.dialog.kind === "edit" ? view.dialog.row.id : "create"}
          {...(view.dialog.kind === "edit" ? { row: view.dialog.row } : {})}
          defaults={{
            ...(search.academicYearId === undefined ? {} : { academicYearId: search.academicYearId }),
            ...(search.gradeId === undefined ? {} : { gradeId: search.gradeId }),
          }}
          // The server refuses a closed year and an inactive grade, so they are not offered.
          yearOptions={optionsOf(years.rows.filter((year) => year.isActive && year.status !== "CLOSED"))}
          gradeOptions={optionsOf(gradeLabels.filter((grade) => grade.isActive))}
          yearName={view.dialog.kind === "edit" ? (yearNames.get(view.dialog.row.academicYearId) ?? unknown) : ""}
          gradeName={view.dialog.kind === "edit" ? (gradeNames.get(view.dialog.row.gradeId) ?? unknown) : ""}
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
