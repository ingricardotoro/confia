import type { TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { ActiveBadge, OptionField, RowActions, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import {
  ResourceScreen,
  type ListTableState,
  type ResourceNotice,
  type ResourcePagination,
} from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { PAYMENT_CATEGORIES } from "../catalog-search.ts";
import { CATEGORY_LABEL_KEYS } from "../category-options.ts";
import type { PaymentConcept } from "../payment-concept-form.ts";

export interface PaymentConceptsViewProps {
  readonly rows: readonly PaymentConcept[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  /** The two filters. An empty value means "all". */
  readonly filters: {
    readonly category: { readonly value: string; readonly onChange: (category: string) => void };
    readonly active: { readonly value: string; readonly onChange: (active: string) => void };
  };
  readonly onCreate: () => void;
  readonly onEdit: (row: PaymentConcept) => void;
  readonly onToggle: (row: PaymentConcept) => void;
}

/** The payment concepts as a table: code, name, category, recurrence, tax condition, tax rate, state and actions, with two filters. */
export function PaymentConceptsView({ rows, table, pagination, notice, togglingId, filters, onCreate, onEdit, onToggle }: PaymentConceptsViewProps) {
  const { t } = useAdminText();
  const yesNo = (value: boolean) => t(value ? "catalog.common.yes" : "catalog.common.no");
  const categoryOptions: readonly Option[] = PAYMENT_CATEGORIES.map((category) => ({ value: category, label: t(CATEGORY_LABEL_KEYS[category]) }));
  const activeOptions: readonly Option[] = [
    { value: "true", label: t("resource.active") },
    { value: "false", label: t("resource.inactive") },
  ];
  const columns: readonly TableColumn<PaymentConcept>[] = [
    { id: "code", header: t("catalog.paymentConcepts.columns.code"), sortable: true, cell: (row) => row.code },
    { id: "name", header: t("catalog.paymentConcepts.columns.name"), sortable: true, cell: (row) => row.name },
    { id: "category", header: t("catalog.paymentConcepts.columns.category"), cell: (row) => t(CATEGORY_LABEL_KEYS[row.category]) },
    { id: "isRecurring", header: t("catalog.paymentConcepts.columns.recurring"), cell: (row) => yesNo(row.isRecurring) },
    { id: "isTaxable", header: t("catalog.paymentConcepts.columns.taxable"), cell: (row) => yesNo(row.isTaxable) },
    {
      id: "taxRate",
      header: t("catalog.paymentConcepts.columns.taxRate"),
      cell: (row) =>
        row.taxRateCode === null || row.taxRateCode === undefined
          ? t(row.isTaxable ? "catalog.paymentConcepts.taxRateMissing" : "catalog.paymentConcepts.taxRateNone")
          : t("catalog.taxRates.optionLabel", { code: row.taxRateCode, name: row.taxRateName ?? "" }),
    },
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
      heading={t("catalog.paymentConcepts.heading")}
      createLabel={t("catalog.paymentConcepts.create")}
      onCreate={onCreate}
      notice={notice}
      filters={
        <>
          <OptionField
            label={t("catalog.paymentConcepts.filterCategory")}
            controlId="payment-concepts-filter-category"
            value={filters.category.value}
            options={categoryOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.category.onChange}
          />
          <OptionField
            label={t("catalog.paymentConcepts.filterActive")}
            controlId="payment-concepts-filter-active"
            value={filters.active.value}
            options={activeOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.active.onChange}
          />
        </>
      }
      table={{ ...table, caption: t("catalog.paymentConcepts.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
