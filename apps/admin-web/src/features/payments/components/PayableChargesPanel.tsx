import { ErrorState, MoneyAmount, Pagination, Skeleton, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import type { Payment, PayableCharge } from "../payments-types.ts";

export type PayableChargesState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly description: string; readonly onRetry: () => void }
  /** The charges the server says are pending, in the order a payment is allocated to them, one page as the server paged it. */
  | { readonly kind: "ready"; readonly rows: readonly PayableCharge[]; readonly pagination: ChargesPagination };

export interface ChargesPagination {
  readonly page: number;
  readonly size: number;
  readonly total: number;
  readonly onPageChange: (page: number) => void;
  /** A page is being fetched: both buttons wait. */
  readonly busy: boolean;
}

export type StudentBalanceState =
  | { readonly kind: "loading" }
  | { readonly kind: "unavailable" }
  /** What the statement of the student closes with, as the server derived it from the ledger. */
  | { readonly kind: "ready"; readonly amount: Payment["amount"] };

export interface PayableChargesPanelProps {
  readonly charges: PayableChargesState;
  readonly balance: StudentBalanceState;
  readonly onViewCharges: () => void;
}

/**
 * What the student owes, before the payment is registered: the charges in force with what each has paid and what is still owed, and the balance
 * of the student. Every figure is the server own, shown as it arrived: the browser adds nothing up, so a payment is never split here, the
 * server splits it when it is registered and the result says how.
 */
export function PayableChargesPanel({ charges, balance, onViewCharges }: PayableChargesPanelProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<PayableCharge>[] = [
    { id: "period", header: t("payments.register.columns.period"), cell: (row) => row.periodLabel },
    { id: "concept", header: t("payments.register.columns.concept"), cell: (row) => row.conceptName ?? row.conceptCode ?? t("payments.register.noConcept") },
    { id: "dueOn", header: t("payments.register.columns.dueOn"), cell: (row) => formatIsoDate(row.dueOn) },
    { id: "total", header: t("payments.register.columns.total"), align: "end", cell: (row) => <MoneyAmount value={row.total} size="sm" /> },
    { id: "paid", header: t("payments.register.columns.paid"), align: "end", cell: (row) => <MoneyAmount value={row.paid} size="sm" /> },
    { id: "outstanding", header: t("payments.register.columns.outstanding"), align: "end", cell: (row) => <MoneyAmount value={row.outstanding} size="sm" emphasis="strong" /> },
  ];

  return (
    <section aria-labelledby="payable-charges-heading" className="flex flex-col gap-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 id="payable-charges-heading" className="text-base font-semibold text-text-primary">
          {t("payments.register.chargesHeading")}
        </h3>
        <p className="text-sm text-text-secondary">
          {t("payments.register.balanceLabel")}{" "}
          {balance.kind === "ready" ? <MoneyAmount value={balance.amount} size="sm" emphasis="strong" /> : balance.kind === "loading" ? "…" : t("payments.register.balanceUnavailable")}
        </p>
      </div>
      <p className="text-sm text-text-secondary">{t("payments.register.balanceHelp")}</p>
      {charges.kind === "loading" ? (
        <div aria-busy="true" className="flex flex-col gap-2">
          <p role="status" className="sr-only">
            {t("payments.register.chargesLoading")}
          </p>
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
        </div>
      ) : charges.kind === "error" ? (
        <ErrorState title={t("payments.register.chargesFailed")} description={charges.description} onRetry={charges.onRetry} />
      ) : (
        <>
          <Table
            stickyHeader
            captionHidden
            caption={t("payments.register.chargesCaption")}
            columns={columns}
            rows={charges.rows}
            getRowId={(row) => row.id}
            empty={{
              title: t("payments.register.chargesEmptyTitle"),
              description: t("payments.register.chargesEmptyDescription"),
              action: { label: t("payments.register.chargesLink"), onAction: onViewCharges },
            }}
          />
          <Pagination
            page={charges.pagination.page}
            size={charges.pagination.size}
            total={charges.pagination.total}
            onPageChange={charges.pagination.onPageChange}
            disabled={charges.pagination.busy}
          />
        </>
      )}
    </section>
  );
}
