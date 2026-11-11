import type { TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { ActiveBadge, RowActions } from "../../organization/components/fields.tsx";
import { ResourceScreen, type ListTableState, type ResourceNotice, type ResourcePagination } from "../../organization/components/ResourceScreen.tsx";
import { nameOf, type Student } from "../student-form.ts";
import { StudentFilters, type StudentFiltersProps } from "./StudentFilters.tsx";

export interface StudentsViewProps {
  readonly rows: readonly Student[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  readonly filters: StudentFiltersProps;
  readonly onCreate: () => void;
  readonly onEdit: (row: Student) => void;
  readonly onToggle: (row: Student) => void;
}

/**
 * The students as a table: code, last names, first names, birth date, sex, the masked document, state and actions,
 * with a text search and a status filter. The document is the masked text the server sends, or a dash.
 */
export function StudentsView({ rows, table, pagination, notice, togglingId, filters, onCreate, onEdit, onToggle }: StudentsViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Student>[] = [
    { id: "studentCode", header: t("students.columns.studentCode"), sortable: true, cell: (row) => row.studentCode },
    { id: "lastName", header: t("students.columns.lastName"), sortable: true, cell: (row) => row.lastName },
    { id: "firstName", header: t("students.columns.firstName"), sortable: true, cell: (row) => row.firstName },
    { id: "birthDate", header: t("students.columns.birthDate"), sortable: true, cell: (row) => formatIsoDate(row.birthDate) },
    { id: "sex", header: t("students.columns.sex"), cell: (row) => t(`students.sex.${row.sex}`) },
    {
      id: "nationalId",
      header: t("students.columns.nationalId"),
      cell: (row) =>
        row.nationalIdMasked === null ? (
          <>
            <span aria-hidden="true">{t("students.noDocumentMark")}</span>
            <span className="sr-only">{t("students.noDocumentText")}</span>
          </>
        ) : (
          row.nationalIdMasked
        ),
    },
    { id: "status", header: t("organization.common.state"), cell: (row) => <ActiveBadge active={row.status === "ACTIVE"} /> },
    {
      id: "actions",
      header: t("organization.common.actions"),
      cell: (row) => (
        <RowActions
          name={nameOf(row)}
          active={row.status === "ACTIVE"}
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
      heading={t("students.heading")}
      createLabel={t("students.create")}
      onCreate={onCreate}
      notice={notice}
      filters={<StudentFilters {...filters} />}
      table={{ ...table, caption: t("students.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
