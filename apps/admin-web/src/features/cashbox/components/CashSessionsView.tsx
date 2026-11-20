import { MoneyAmount, PageHeader, Pagination, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant } from "../../../shared/format/format-date.ts";
import type { ListTableState, ResourcePagination } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { OptionField, TextField } from "../../../shared/resource-screen/components/fields.tsx";
import { CASH_SESSION_STATUSES } from "../cashbox-search.ts";
import type { CashSessionRow } from "../cashbox-types.ts";
import { CashSessionStatusBadge } from "./CashSessionStatusBadge.tsx";

export interface CashSessionsViewProps {
  readonly filters: {
    readonly status: { readonly value: string; readonly onChange: (status: string) => void };
    readonly from: { readonly value: string; readonly onChange: (from: string) => void };
    readonly to: { readonly value: string; readonly error?: string | undefined; readonly onChange: (to: string) => void };
  };
  readonly rows: readonly CashSessionRow[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  /** The address of a session, so a link opens in a new tab and a plain click is a navigation of the router. */
  readonly hrefOf: (row: CashSessionRow) => string;
  readonly onOpen: (row: CashSessionRow) => void;
}

const NONE = "—";

/**
 * The sessions of the cashbox as the server pages, filters and orders them: when each opened, who the cashier is (by the identifier the
 * server gives), its state and its amounts. The expected cash, the counted cash and the difference are the server's, shown as they arrived
 * (a session still open has no count yet). A row opens its detail, the base of the closing report.
 */
export function CashSessionsView({ filters, rows, table, pagination, hrefOf, onOpen }: CashSessionsViewProps) {
  const { t } = useAdminText();

  const columns: readonly TableColumn<CashSessionRow>[] = [
    {
      id: "openedAt",
      header: t("cashbox.sessions.columns.openedAt"),
      sortable: true,
      cell: (row) => (
        <a
          href={hrefOf(row)}
          className="font-medium text-text-primary tabular-nums underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
          onClick={(event) => {
            if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            onOpen(row);
          }}
        >
          {formatInstant(row.openedAt)}
        </a>
      ),
    },
    { id: "cashier", header: t("cashbox.sessions.columns.cashier"), cell: (row) => <code className="font-identifier text-xs">{row.cashierId.slice(0, 8)}</code> },
    { id: "status", header: t("cashbox.sessions.columns.status"), sortable: true, cell: (row) => <CashSessionStatusBadge status={row.status} /> },
    { id: "openingFloat", header: t("cashbox.sessions.columns.openingFloat"), align: "end", cell: (row) => <MoneyAmount value={row.openingFloat} size="sm" /> },
    { id: "expected", header: t("cashbox.sessions.columns.expected"), align: "end", cell: (row) => (row.expectedAmount === undefined ? NONE : <MoneyAmount value={row.expectedAmount} size="sm" />) },
    { id: "declared", header: t("cashbox.sessions.columns.declared"), align: "end", cell: (row) => (row.declaredAmount === undefined ? NONE : <MoneyAmount value={row.declaredAmount} size="sm" />) },
    {
      id: "difference",
      header: t("cashbox.sessions.columns.difference"),
      align: "end",
      cell: (row) => (row.differenceAmount === undefined ? NONE : <MoneyAmount value={row.differenceAmount} size="sm" colorBySign showPositiveSign />),
    },
  ];

  return (
    <section aria-labelledby="cash-sessions-heading" className="flex flex-col gap-3">
      <PageHeader title={t("cashbox.sessions.heading")} headingLevel={2} headingId="cash-sessions-heading" description={t("cashbox.sessions.description")} />
      <div role="group" aria-label={t("resource.filters")} className="flex flex-wrap items-end gap-x-3 gap-y-2 rounded-lg border border-border-subtle bg-bg-surface px-3 py-2">
        <OptionField
          label={t("cashbox.sessions.filterStatus")}
          controlId="cash-sessions-status"
          value={filters.status.value}
          placeholder={t("resource.allOption")}
          options={CASH_SESSION_STATUSES.map((status) => ({ value: status, label: t(`cashbox.statuses.${status}`) }))}
          onChange={filters.status.onChange}
        />
        <TextField label={t("cashbox.sessions.filterFrom")} controlId="cash-sessions-from" kind="date" value={filters.from.value} onChange={filters.from.onChange} onBlur={() => undefined} />
        <TextField
          label={t("cashbox.sessions.filterTo")}
          controlId="cash-sessions-to"
          kind="date"
          value={filters.to.value}
          error={filters.to.error}
          onChange={filters.to.onChange}
          onBlur={() => undefined}
        />
      </div>
      <Table stickyHeader captionHidden {...table} caption={t("cashbox.sessions.caption")} columns={columns} rows={rows} getRowId={(row) => row.id} />
      <Pagination page={pagination.page} size={pagination.size} total={pagination.total} onPageChange={pagination.onPageChange} disabled={pagination.busy} />
    </section>
  );
}
