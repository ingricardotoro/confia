import { MoneyAmount, PageHeader, Pagination, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant, formatIsoDate } from "../../../shared/format/format-date.ts";
import type { ListTableState, ResourcePagination } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { OptionField, TextField } from "../../../shared/resource-screen/components/fields.tsx";
import { PAYMENT_METHODS, PAYMENT_STATUSES } from "../payments-search.ts";
import type { PaymentRow } from "../payments-types.ts";
import { PaymentStatusBadge } from "./PaymentStatusBadge.tsx";

export interface PaymentsViewProps {
  readonly filters: {
    readonly status: { readonly value: string; readonly onChange: (status: string) => void };
    readonly method: { readonly value: string; readonly onChange: (method: string) => void };
    readonly from: { readonly value: string; readonly onChange: (from: string) => void };
    readonly to: { readonly value: string; readonly error?: string | undefined; readonly onChange: (to: string) => void };
  };
  readonly rows: readonly PaymentRow[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  /** The address of a payment, so a link opens in a new tab and a plain click is a navigation of the router. */
  readonly hrefOf: (row: PaymentRow) => string;
  readonly onOpen: (row: PaymentRow) => void;
}

/**
 * The payments as the server pages, filters and orders them: when each was received, for which student, how, the reference of a transfer, the
 * amount and the state. Every amount is the server own, shown as it arrived. A row opens its detail, where a declared transfer is decided.
 */
export function PaymentsView({ filters, rows, table, pagination, hrefOf, onOpen }: PaymentsViewProps) {
  const { t } = useAdminText();

  const columns: readonly TableColumn<PaymentRow>[] = [
    {
      id: "receivedOn",
      header: t("payments.list.columns.receivedOn"),
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
          {formatIsoDate(row.receivedOn)}
        </a>
      ),
    },
    { id: "student", header: t("payments.list.columns.student"), cell: (row) => row.studentCode ?? t("payments.list.noReference") },
    { id: "method", header: t("payments.list.columns.method"), cell: (row) => t(`payments.methods.${row.method}`) },
    { id: "reference", header: t("payments.list.columns.reference"), cell: (row) => row.reference ?? t("payments.list.noReference") },
    { id: "amount", header: t("payments.list.columns.amount"), sortable: true, align: "end", cell: (row) => <MoneyAmount value={row.amount} size="sm" emphasis="strong" /> },
    { id: "status", header: t("payments.list.columns.status"), cell: (row) => <PaymentStatusBadge status={row.status} /> },
    { id: "registeredAt", header: t("payments.list.columns.registeredAt"), sortable: true, cell: (row) => <span className="tabular-nums">{formatInstant(row.registeredAt)}</span> },
  ];

  return (
    <section aria-labelledby="payments-heading" className="flex flex-col gap-3">
      <PageHeader title={t("payments.list.heading")} headingLevel={2} headingId="payments-heading" description={t("payments.list.description")} />
      <div role="group" aria-label={t("resource.filters")} className="flex flex-wrap items-end gap-x-3 gap-y-2 rounded-lg border border-border-subtle bg-bg-surface px-3 py-2">
        <OptionField
          label={t("payments.list.filterStatus")}
          controlId="payments-status"
          value={filters.status.value}
          placeholder={t("resource.allOption")}
          options={PAYMENT_STATUSES.map((status) => ({ value: status, label: t(`payments.statuses.${status}`) }))}
          onChange={filters.status.onChange}
        />
        <OptionField
          label={t("payments.list.filterMethod")}
          controlId="payments-method"
          value={filters.method.value}
          placeholder={t("resource.allOption")}
          options={PAYMENT_METHODS.map((method) => ({ value: method, label: t(`payments.methods.${method}`) }))}
          onChange={filters.method.onChange}
        />
        <TextField label={t("payments.list.filterFrom")} controlId="payments-from" kind="date" value={filters.from.value} onChange={filters.from.onChange} onBlur={() => undefined} />
        <TextField
          label={t("payments.list.filterTo")}
          controlId="payments-to"
          kind="date"
          value={filters.to.value}
          error={filters.to.error}
          onChange={filters.to.onChange}
          onBlur={() => undefined}
        />
      </div>
      <Table stickyHeader captionHidden {...table} caption={t("payments.list.caption")} columns={columns} rows={rows} getRowId={(row) => row.id} />
      <Pagination page={pagination.page} size={pagination.size} total={pagination.total} onPageChange={pagination.onPageChange} disabled={pagination.busy} />
    </section>
  );
}
