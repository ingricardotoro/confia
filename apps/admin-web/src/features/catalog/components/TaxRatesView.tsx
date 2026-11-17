import type { TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { ActiveBadge, OptionField, RowActions, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import {
  ResourceScreen,
  type ListTableState,
  type ResourceNotice,
  type ResourcePagination,
} from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { TaxRate } from "../tax-rate-form.ts";

export interface TaxRatesViewProps {
  readonly rows: readonly TaxRate[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  /** The one filter. An empty value means "all". */
  readonly filters: {
    readonly active: { readonly value: string; readonly onChange: (active: string) => void };
  };
  /** The address of a rate, so a link opens in a new tab and a plain click is a navigation of the router. */
  readonly hrefOf: (row: TaxRate) => string;
  readonly onOpen: (row: TaxRate) => void;
  readonly onCreate: () => void;
  readonly onEdit: (row: TaxRate) => void;
  readonly onToggle: (row: TaxRate) => void;
}

/**
 * The tax rates as a table: code (a link to its detail, where the percentages are), name, state and actions, with a
 * filter by state. The list shows no percentage: a rate has one for each validity, and the detail holds them.
 */
export function TaxRatesView({ rows, table, pagination, notice, togglingId, filters, hrefOf, onOpen, onCreate, onEdit, onToggle }: TaxRatesViewProps) {
  const { t } = useAdminText();
  const activeOptions: readonly Option[] = [
    { value: "true", label: t("resource.active") },
    { value: "false", label: t("resource.inactive") },
  ];
  const columns: readonly TableColumn<TaxRate>[] = [
    {
      id: "code",
      header: t("catalog.taxRates.columns.code"),
      sortable: true,
      cell: (row) => (
        <a
          href={hrefOf(row)}
          className="font-medium text-text-primary underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
          onClick={(event) => {
            if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            onOpen(row);
          }}
        >
          {row.code}
        </a>
      ),
    },
    { id: "name", header: t("catalog.taxRates.columns.name"), sortable: true, cell: (row) => row.name },
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
      heading={t("catalog.taxRates.heading")}
      createLabel={t("catalog.taxRates.create")}
      onCreate={onCreate}
      notice={notice}
      filters={
        <OptionField
          label={t("catalog.taxRates.filterActive")}
          controlId="tax-rates-filter-active"
          value={filters.active.value}
          options={activeOptions}
          placeholder={t("resource.allOption")}
          onChange={filters.active.onChange}
        />
      }
      table={{ ...table, caption: t("catalog.taxRates.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
