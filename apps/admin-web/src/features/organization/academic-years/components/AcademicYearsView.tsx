import type { TableColumn } from "@confia/ui";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../../shared/format/format-date.ts";
import { ActiveBadge, RowActions } from "../../../../shared/resource-screen/components/fields.tsx";
import { ResourceScreen, type ListTableState, type ResourceNotice, type ResourcePagination } from "../../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { AcademicYear } from "../academic-year-form.ts";
import { STATUS_KEYS } from "./AcademicYearForm.tsx";

export interface AcademicYearsViewProps {
  readonly rows: readonly AcademicYear[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  readonly onCreate: () => void;
  readonly onEdit: (row: AcademicYear) => void;
  readonly onToggle: (row: AcademicYear) => void;
}

/** The academic years as a table: name, the two dates, the stage, the state and the actions. */
export function AcademicYearsView({ rows, table, pagination, notice, togglingId, onCreate, onEdit, onToggle }: AcademicYearsViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<AcademicYear>[] = [
    { id: "name", header: t("organization.academicYears.columns.name"), sortable: true, cell: (row) => row.name },
    { id: "startDate", header: t("organization.academicYears.columns.startDate"), sortable: true, cell: (row) => formatIsoDate(row.startDate) },
    { id: "endDate", header: t("organization.academicYears.columns.endDate"), sortable: true, cell: (row) => formatIsoDate(row.endDate) },
    { id: "status", header: t("organization.academicYears.columns.status"), sortable: true, cell: (row) => t(STATUS_KEYS[row.status]) },
    { id: "isActive", header: t("resource.state"), cell: (row) => <ActiveBadge active={row.isActive} /> },
    {
      id: "actions",
      header: t("resource.actions"),
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
      heading={t("organization.academicYears.heading")}
      createLabel={t("organization.academicYears.create")}
      onCreate={onCreate}
      notice={notice}
      table={{ ...table, caption: t("organization.academicYears.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
