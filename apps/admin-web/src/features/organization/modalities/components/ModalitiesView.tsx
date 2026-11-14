import type { TableColumn } from "@confia/ui";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { ActiveBadge, RowActions } from "../../components/fields.tsx";
import { ResourceScreen, type ListTableState, type ResourceNotice, type ResourcePagination } from "../../components/ResourceScreen.tsx";
import type { Modality } from "../modality-form.ts";

export interface ModalitiesViewProps {
  readonly rows: readonly Modality[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  readonly onCreate: () => void;
  readonly onEdit: (row: Modality) => void;
  readonly onToggle: (row: Modality) => void;
}

/** The modalities as a table: code, name, state and actions. */
export function ModalitiesView({ rows, table, pagination, notice, togglingId, onCreate, onEdit, onToggle }: ModalitiesViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Modality>[] = [
    { id: "code", header: t("organization.modalities.columns.code"), sortable: true, cell: (row) => row.code },
    { id: "name", header: t("organization.modalities.columns.name"), sortable: true, cell: (row) => row.name },
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
      heading={t("organization.modalities.heading")}
      createLabel={t("organization.modalities.create")}
      onCreate={onCreate}
      notice={notice}
      table={{ ...table, caption: t("organization.modalities.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
