import { Badge, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { OptionField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import {
  ResourceScreen,
  type ListTableState,
  type ResourceNotice,
  type ResourcePagination,
} from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { PRICE_LIST_SCOPES, PRICE_LIST_STATUSES } from "../catalog-search.ts";
import type { PriceList } from "../price-list-form.ts";

export interface PriceListsViewProps {
  readonly rows: readonly PriceList[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** Names the year, the modality or the grade a row points to, by id. A missing id shows "not available". */
  readonly yearNames: ReadonlyMap<string, string>;
  readonly referenceNames: ReadonlyMap<string, string>;
  /** The year options of the filter. */
  readonly yearOptions: readonly Option[];
  /** Set when the lookups of the years, the modalities or the grades failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The three filters. An empty value means "all". */
  readonly filters: {
    readonly year: { readonly value: string; readonly onChange: (year: string) => void };
    readonly scope: { readonly value: string; readonly onChange: (scope: string) => void };
    readonly status: { readonly value: string; readonly onChange: (status: string) => void };
  };
  /** The address of a list, so a link opens in a new tab and a plain click is a navigation of the router. */
  readonly hrefOf: (row: PriceList) => string;
  readonly onOpen: (row: PriceList) => void;
  readonly onCreate: () => void;
}

/** The price lists as a table: name (a link to its detail), year, scope, the modality or grade it points to, and state. */
export function PriceListsView({
  rows,
  table,
  pagination,
  notice,
  yearNames,
  referenceNames,
  yearOptions,
  lookupFailure,
  filters,
  hrefOf,
  onOpen,
  onCreate,
}: PriceListsViewProps) {
  const { t } = useAdminText();
  const unknown = t("resource.unknownReference");
  const scopeOptions: readonly Option[] = PRICE_LIST_SCOPES.map((scope) => ({ value: scope, label: t(`catalog.priceLists.scopes.${scope}`) }));
  const statusOptions: readonly Option[] = PRICE_LIST_STATUSES.map((status) => ({ value: status, label: t(`catalog.priceLists.statuses.${status}`) }));

  const columns: readonly TableColumn<PriceList>[] = [
    {
      id: "name",
      header: t("catalog.priceLists.columns.name"),
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
          {row.name}
        </a>
      ),
    },
    { id: "year", header: t("catalog.priceLists.columns.year"), cell: (row) => yearNames.get(row.academicYearId) ?? unknown },
    { id: "scope", header: t("catalog.priceLists.columns.scope"), cell: (row) => t(`catalog.priceLists.scopes.${row.scope}`) },
    {
      id: "reference",
      header: t("catalog.priceLists.columns.reference"),
      cell: (row) =>
        row.scopeRefId === null || row.scopeRefId === undefined ? t("catalog.priceLists.notApplicable") : (referenceNames.get(row.scopeRefId) ?? unknown),
    },
    {
      id: "status",
      header: t("catalog.priceLists.columns.status"),
      cell: (row) => <Badge variant={row.status === "ACTIVE" ? "primary" : "outline"}>{t(`catalog.priceLists.statuses.${row.status}`)}</Badge>,
    },
  ];

  return (
    <ResourceScreen
      heading={t("catalog.priceLists.heading")}
      createLabel={t("catalog.priceLists.create")}
      onCreate={onCreate}
      notice={notice}
      lookupFailure={lookupFailure}
      filters={
        <>
          <OptionField
            label={t("catalog.priceLists.filterYear")}
            controlId="price-lists-filter-year"
            value={filters.year.value}
            options={yearOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.year.onChange}
          />
          <OptionField
            label={t("catalog.priceLists.filterScope")}
            controlId="price-lists-filter-scope"
            value={filters.scope.value}
            options={scopeOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.scope.onChange}
          />
          <OptionField
            label={t("catalog.priceLists.filterStatus")}
            controlId="price-lists-filter-status"
            value={filters.status.value}
            options={statusOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.status.onChange}
          />
        </>
      }
      table={{ ...table, caption: t("catalog.priceLists.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
