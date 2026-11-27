import { MoneyAmount, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { ActiveBadge, OptionField, RowActions } from "../../../shared/resource-screen/components/fields.tsx";
import {
  ResourceScreen,
  type ListTableState,
  type ResourceNotice,
  type ResourcePagination,
} from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { Discount } from "../discounts-types.ts";
import { DISCOUNT_KINDS } from "../discounts-search.ts";
import { BENEFIT_TYPES } from "../scholarships-search.ts";

export interface DiscountsViewProps {
  readonly rows: readonly Discount[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  /** The three filters. An empty value means "all". */
  readonly filters: {
    readonly kind: { readonly value: string; readonly onChange: (kind: string) => void };
    readonly benefitType: { readonly value: string; readonly onChange: (benefitType: string) => void };
    readonly active: { readonly value: string; readonly onChange: (active: string) => void };
  };
  /** Absent when the person cannot create: the button is not drawn. */
  readonly onCreate?: (() => void) | undefined;
  /** Absent when the person cannot edit: the button is not drawn. */
  readonly onEdit?: ((row: Discount) => void) | undefined;
  /** Absent when the person cannot change the state: the button is not drawn. */
  readonly onToggle?: ((row: Discount) => void) | undefined;
}

/**
 * The discounts as a table: code, name, class (campaign or agreement), the benefit (a percentage or a fixed amount, as the server holds it), the
 * validity, whether it stacks, the concepts it covers, the state and the actions, with three filters. Every amount is the server own, shown as it arrived.
 */
export function DiscountsView({ rows, table, pagination, notice, togglingId, filters, onCreate, onEdit, onToggle }: DiscountsViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Discount>[] = [
    { id: "code", header: t("scholarships.discounts.columns.code"), sortable: true, cell: (row) => row.code },
    { id: "name", header: t("scholarships.discounts.columns.name"), sortable: true, cell: (row) => row.name },
    { id: "kind", header: t("scholarships.discounts.columns.kind"), cell: (row) => t(`scholarships.discounts.kinds.${row.kind}`) },
    {
      id: "benefit",
      header: t("scholarships.discounts.columns.benefit"),
      align: "end",
      cell: (row) =>
        row.benefitType === "FIXED_AMOUNT" && row.fixedAmount !== undefined ? (
          <MoneyAmount value={row.fixedAmount} size="sm" emphasis="strong" />
        ) : (
          <span className="tabular-nums">{t("scholarships.list.percentValue", { percent: row.percent ?? "" })}</span>
        ),
    },
    {
      id: "validFrom",
      header: t("scholarships.discounts.columns.validity"),
      sortable: true,
      cell: (row) => <span className="tabular-nums">{t("scholarships.discounts.validityValue", { from: formatIsoDate(row.validFrom), to: formatIsoDate(row.validTo) })}</span>,
    },
    { id: "stackable", header: t("scholarships.discounts.columns.stackable"), cell: (row) => t(row.stackable ? "scholarships.discounts.stackableYes" : "scholarships.discounts.stackableNo") },
    { id: "concepts", header: t("scholarships.discounts.columns.concepts"), cell: (row) => row.concepts.map((concept) => concept.name).join(", ") },
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
      heading={t("scholarships.discounts.heading")}
      description={t("scholarships.discounts.description")}
      createLabel={t("scholarships.discounts.create")}
      onCreate={onCreate}
      notice={notice}
      filters={
        <>
          <OptionField
            label={t("scholarships.discounts.filterKind")}
            controlId="discounts-filter-kind"
            value={filters.kind.value}
            options={DISCOUNT_KINDS.map((kind) => ({ value: kind, label: t(`scholarships.discounts.kinds.${kind}`) }))}
            placeholder={t("resource.allOption")}
            onChange={filters.kind.onChange}
          />
          <OptionField
            label={t("scholarships.list.filterBenefitType")}
            controlId="discounts-filter-benefit-type"
            value={filters.benefitType.value}
            options={BENEFIT_TYPES.map((type) => ({ value: type, label: t(`scholarships.benefitTypes.${type}`) }))}
            placeholder={t("resource.allOption")}
            onChange={filters.benefitType.onChange}
          />
          <OptionField
            label={t("scholarships.list.filterActive")}
            controlId="discounts-filter-active"
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
      table={{ ...table, caption: t("scholarships.discounts.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
