import { MoneyAmount, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant } from "../../../shared/format/format-date.ts";
import type { CashMovement } from "../cashbox-types.ts";

export interface MovementsTableProps {
  readonly movements: readonly CashMovement[];
  /** The way forward of an empty table (register a deposit on the open session, or go back to the list on a report): an empty list always has one. Already translated. */
  readonly emptyAction: { readonly label: string; readonly onAction: () => void };
}

/**
 * The movements of a session in the order the server gave them. Each amount is shown as it arrived with its type beside it: a withdrawal
 * is not a negative number here, it is a withdrawal. Nothing is added up: the expected cash is the server's.
 */
export function MovementsTable({ movements, emptyAction }: MovementsTableProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<CashMovement>[] = [
    { id: "createdAt", header: t("cashbox.movements.columns.createdAt"), cell: (row) => <span className="tabular-nums">{formatInstant(row.createdAt)}</span> },
    { id: "type", header: t("cashbox.movements.columns.type"), cell: (row) => t(`cashbox.movementTypes.${row.type}`) },
    { id: "amount", header: t("cashbox.movements.columns.amount"), align: "end", cell: (row) => <MoneyAmount value={row.amount} size="sm" /> },
    { id: "notes", header: t("cashbox.movements.columns.notes"), cell: (row) => row.notes ?? t("cashbox.movements.noNotes") },
  ];
  return (
    <Table
      stickyHeader
      captionHidden
      caption={t("cashbox.movements.caption")}
      columns={columns}
      rows={movements}
      getRowId={(row) => row.id}
      empty={{
        title: t("cashbox.movements.emptyTitle"),
        description: t("cashbox.movements.emptyDescription"),
        action: emptyAction,
      }}
    />
  );
}
