import { MoneyAmount, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import type { Allocation } from "../payments-types.ts";

export interface AllocationsTableProps {
  readonly allocations: readonly Allocation[];
  /** The way forward of an empty table: the payment left everything in favor of the student, so the statement is where to see it. Already translated. */
  readonly emptyAction: { readonly label: string; readonly onAction: () => void };
}

/**
 * The charges a payment covered, in the order the server applied them, each with the amount it applied. Every figure is the server own: the
 * browser neither splits the payment nor adds the parts up. A payment with no allocation (the student had nothing pending) says so in words.
 */
export function AllocationsTable({ allocations, emptyAction }: AllocationsTableProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Allocation>[] = [
    { id: "period", header: t("payments.allocations.columns.period"), cell: (row) => row.periodLabel ?? t("payments.detail.noValue") },
    { id: "concept", header: t("payments.allocations.columns.concept"), cell: (row) => row.conceptName ?? row.conceptCode ?? t("payments.allocations.noConcept") },
    { id: "dueOn", header: t("payments.allocations.columns.dueOn"), cell: (row) => (row.dueOn == null ? t("payments.detail.noValue") : formatIsoDate(row.dueOn)) },
    { id: "amount", header: t("payments.allocations.columns.amount"), align: "end", cell: (row) => <MoneyAmount value={row.amount} size="sm" emphasis="strong" /> },
  ];
  return (
    <Table
      stickyHeader
      captionHidden
      caption={t("payments.allocations.caption")}
      columns={columns}
      rows={allocations}
      getRowId={(row) => row.chargeId}
      empty={{ title: t("payments.allocations.emptyTitle"), description: t("payments.allocations.emptyDescription"), action: emptyAction }}
    />
  );
}
