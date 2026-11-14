import type { TableColumn } from "@confia/ui";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { ActiveBadge, OptionField, RowActions, type Option } from "../../components/fields.tsx";
import { ResourceScreen, type ListTableState, type ResourceNotice, type ResourcePagination } from "../../components/ResourceScreen.tsx";
import type { Section } from "../section-form.ts";

interface SelectFilter {
  readonly value: string;
  readonly options: readonly Option[];
  readonly onChange: (id: string) => void;
}

export interface SectionsViewProps {
  readonly rows: readonly Section[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  /** The names of the years and of the grades by id, to say which ones each section belongs to. */
  readonly yearNames: ReadonlyMap<string, string>;
  readonly gradeNames: ReadonlyMap<string, string>;
  readonly yearFilter: SelectFilter;
  readonly gradeFilter: SelectFilter;
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly onCreate: () => void;
  readonly onEdit: (row: Section) => void;
  readonly onToggle: (row: Section) => void;
}

/** The sections as a table: name, year, grade, capacity, state and actions, with filters by year and by grade. */
export function SectionsView({
  rows,
  table,
  pagination,
  notice,
  togglingId,
  yearNames,
  gradeNames,
  yearFilter,
  gradeFilter,
  lookupFailure,
  onCreate,
  onEdit,
  onToggle,
}: SectionsViewProps) {
  const { t } = useAdminText();
  const unknown = t("organization.common.unknownReference");
  const columns: readonly TableColumn<Section>[] = [
    { id: "name", header: t("organization.sections.columns.name"), sortable: true, cell: (row) => row.name },
    { id: "academicYear", header: t("organization.sections.columns.academicYear"), cell: (row) => yearNames.get(row.academicYearId) ?? unknown },
    { id: "grade", header: t("organization.sections.columns.grade"), cell: (row) => gradeNames.get(row.gradeId) ?? unknown },
    {
      id: "capacity",
      header: t("organization.sections.columns.capacity"),
      sortable: true,
      align: "end",
      cell: (row) => row.capacity ?? t("organization.common.noCapacity"),
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
      heading={t("organization.sections.heading")}
      createLabel={t("organization.sections.create")}
      onCreate={onCreate}
      notice={notice}
      lookupFailure={lookupFailure}
      filters={
        <>
          <OptionField
            label={t("organization.sections.filterAcademicYear")}
            controlId="sections-filter-year"
            value={yearFilter.value}
            options={yearFilter.options}
            placeholder={t("organization.common.allOption")}
            onChange={yearFilter.onChange}
          />
          <OptionField
            label={t("organization.sections.filterGrade")}
            controlId="sections-filter-grade"
            value={gradeFilter.value}
            options={gradeFilter.options}
            placeholder={t("organization.common.allOption")}
            onChange={gradeFilter.onChange}
          />
        </>
      }
      table={{ ...table, caption: t("organization.sections.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
