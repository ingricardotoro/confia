import { MoneyAmount, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { ActiveBadge, OptionField, RowActions } from "../../../shared/resource-screen/components/fields.tsx";
import {
  ResourceScreen,
  type ListTableState,
  type ResourceNotice,
  type ResourcePagination,
} from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { Scholarship } from "../scholarships-types.ts";
import { BENEFIT_TYPES } from "../scholarships-search.ts";

export interface ScholarshipsViewProps {
  readonly rows: readonly Scholarship[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  /** The two filters. An empty value means "all". */
  readonly filters: {
    readonly benefitType: { readonly value: string; readonly onChange: (benefitType: string) => void };
    readonly active: { readonly value: string; readonly onChange: (active: string) => void };
  };
  /** Absent when the person cannot create: the button is not drawn. */
  readonly onCreate?: (() => void) | undefined;
  /** Absent when the person cannot edit: the button is not drawn. */
  readonly onEdit?: ((row: Scholarship) => void) | undefined;
  /** Absent when the person cannot change the state: the button is not drawn. */
  readonly onToggle?: ((row: Scholarship) => void) | undefined;
}

/**
 * The scholarships as a table: code, name, the benefit (a percentage or a fixed amount, as the server holds it), the cap per period, the concepts it
 * covers, the state and the actions, with two filters. Every amount is the server own, shown as it arrived.
 */
export function ScholarshipsView({ rows, table, pagination, notice, togglingId, filters, onCreate, onEdit, onToggle }: ScholarshipsViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Scholarship>[] = [
    { id: "code", header: t("scholarships.list.columns.code"), sortable: true, cell: (row) => row.code },
    { id: "name", header: t("scholarships.list.columns.name"), sortable: true, cell: (row) => row.name },
    { id: "benefitType", header: t("scholarships.list.columns.benefitType"), cell: (row) => t(`scholarships.benefitTypes.${row.benefitType}`) },
    {
      id: "benefit",
      header: t("scholarships.list.columns.benefit"),
      align: "end",
      cell: (row) =>
        row.benefitType === "FIXED_AMOUNT" && row.fixedAmount !== undefined ? (
          <MoneyAmount value={row.fixedAmount} size="sm" emphasis="strong" />
        ) : (
          <span className="tabular-nums">{t("scholarships.list.percentValue", { percent: row.percent ?? "" })}</span>
        ),
    },
    {
      id: "periodCap",
      header: t("scholarships.list.columns.periodCap"),
      align: "end",
      cell: (row) => (row.periodCap === undefined ? t("scholarships.list.noCap") : <MoneyAmount value={row.periodCap} size="sm" />),
    },
    { id: "concepts", header: t("scholarships.list.columns.concepts"), cell: (row) => row.concepts.map((concept) => concept.name).join(", ") },
    { id: "active", header: t("resource.state"), cell: (row) => <ActiveBadge active={row.active} /> },
    {
      id: "actions",
      header: t("resource.actions"),
      cell: (row) => (
        <RowActions
          name={row.name}
          active={row.active}
          busy={togglingId === row.id}
          onEdit={
            onEdit === undefined
              ? undefined
              : () => {
                  onEdit(row);
                }
          }
          onToggle={
            onToggle === undefined
              ? undefined
              : () => {
                  onToggle(row);
                }
          }
        />
      ),
    },
  ];

  return (
    <ResourceScreen
      heading={t("scholarships.list.heading")}
      description={t("scholarships.list.description")}
      createLabel={t("scholarships.list.create")}
      onCreate={onCreate}
      notice={notice}
      filters={
        <>
          <OptionField
            label={t("scholarships.list.filterBenefitType")}
            controlId="scholarships-filter-benefit-type"
            value={filters.benefitType.value}
            options={BENEFIT_TYPES.map((type) => ({ value: type, label: t(`scholarships.benefitTypes.${type}`) }))}
            placeholder={t("resource.allOption")}
            onChange={filters.benefitType.onChange}
          />
          <OptionField
            label={t("scholarships.list.filterActive")}
            controlId="scholarships-filter-active"
            value={filters.active.value}
            options={[
              { value: "true", label: t("resource.active") },
              { value: "false", label: t("resource.inactive") },
            ]}
            placeholder={t("resource.allOption")}
            onChange={filters.active.onChange}
          />
        </>
      }
      table={{ ...table, caption: t("scholarships.list.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
