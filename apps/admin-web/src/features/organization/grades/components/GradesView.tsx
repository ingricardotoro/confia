import type { TableColumn } from "@confia/ui";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { ActiveBadge, OptionField, RowActions, type Option } from "../../components/fields.tsx";
import { ResourceScreen, type ListTableState, type ResourceNotice, type ResourcePagination } from "../../components/ResourceScreen.tsx";
import type { Grade } from "../grade-form.ts";

export interface GradesViewProps {
  readonly rows: readonly Grade[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  /** The names of the modalities by id, to say which one each grade belongs to. */
  readonly modalityNames: ReadonlyMap<string, string>;
  readonly modalityFilter: { readonly value: string; readonly options: readonly Option[]; readonly onChange: (modalityId: string) => void };
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly onCreate: () => void;
  readonly onEdit: (row: Grade) => void;
  readonly onToggle: (row: Grade) => void;
}

/** The grades as a table: name, order, modality, state and actions, with a filter by modality. */
export function GradesView({
  rows,
  table,
  pagination,
  notice,
  togglingId,
  modalityNames,
  modalityFilter,
  lookupFailure,
  onCreate,
  onEdit,
  onToggle,
}: GradesViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Grade>[] = [
    { id: "name", header: t("organization.grades.columns.name"), sortable: true, cell: (row) => row.name },
    { id: "ordinal", header: t("organization.grades.columns.ordinal"), sortable: true, align: "end", cell: (row) => row.ordinal },
    {
      id: "modality",
      header: t("organization.grades.columns.modality"),
      cell: (row) => modalityNames.get(row.modalityId) ?? t("organization.common.unknownReference"),
    },
    { id: "isActive", header: t("organization.common.state"), cell: (row) => <ActiveBadge active={row.isActive} /> },
    {
      id: "actions",
      header: t("organization.common.actions"),
      cell: (row) => (
        <RowActions
          name={row.name}
          active={row.isActive}
          busy={togglingId === row.id}
          onEdit={() => {
            onEdit(row);
          }}
          onToggle={() => {
            onToggle(row);
          }}
        />
      ),
    },
  ];

  return (
    <ResourceScreen
      heading={t("organization.grades.heading")}
      createLabel={t("organization.grades.create")}
      onCreate={onCreate}
      notice={notice}
      lookupFailure={lookupFailure}
      filters={
        <OptionField
          label={t("organization.grades.filterModality")}
          controlId="grades-filter-modality"
          value={modalityFilter.value}
          options={modalityFilter.options}
          placeholder={t("organization.common.allOption")}
          onChange={modalityFilter.onChange}
        />
      }
      table={{ ...table, caption: t("organization.grades.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
