import type { ReconciliationResponseOutput } from "@confia/contracts/admin";
import { Pagination, Table, type TableColumn, type TableProps } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant } from "../../../shared/format/format-date.ts";
import type { ResourcePagination } from "../../organization/components/ResourceScreen.tsx";
import { differenceKindOf } from "../reconciliation-model.ts";

type Acta = ReconciliationResponseOutput;

export interface ReconciliationHistoryProps {
  readonly rows: readonly Acta[];
  /** The state of the load (loading, failed with a retry, empty with a way forward), as the server's answer decides it. */
  readonly table: Pick<TableProps<Acta>, "loading" | "error" | "empty">;
  readonly pagination: ResourcePagination;
  /** The name of each year by id, from the years the screen loaded. A year that is not in it is shown with a neutral label. */
  readonly yearNames: ReadonlyMap<string, string>;
}

const NUMBERS = new Intl.NumberFormat("es-HN");

/**
 * The actas signed by the institution, newest first, with the counts they recorded. The account that signed is not shown
 * (its identifier means nothing to the person). The pages are the server's; the table only reports the clicks of the pagination.
 */
export function ReconciliationHistory({ rows, table, pagination, yearNames }: ReconciliationHistoryProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Acta>[] = [
    { id: "signedAt", header: t("imports.reconciliation.history.date"), cell: (row) => formatInstant(row.signedAt) },
    { id: "year", header: t("imports.reconciliation.history.year"), cell: (row) => yearNames.get(row.academicYearId) ?? t("imports.reconciliation.history.unknownYear") },
    { id: "reference", header: t("imports.reconciliation.history.reference"), cell: (row) => row.officialReference },
    { id: "official", header: t("imports.reconciliation.history.official"), align: "end", cell: (row) => NUMBERS.format(row.officialEnrollmentCount) },
    { id: "active", header: t("imports.reconciliation.history.active"), align: "end", cell: (row) => NUMBERS.format(row.enrollmentsActive) },
    {
      id: "difference",
      header: t("imports.reconciliation.history.difference"),
      align: "end",
      cell: (row) => {
        const kind = differenceKindOf(row.difference);
        return t(kind === "more" ? "imports.reconciliation.difference.cellMore" : kind === "fewer" ? "imports.reconciliation.difference.cellFewer" : "imports.reconciliation.difference.cellSame", {
          count: Math.abs(row.difference),
        });
      },
    },
  ];

  return (
    <section aria-labelledby="reconciliation-history-heading" className="flex flex-col gap-3">
      <h3 id="reconciliation-history-heading" className="text-lg font-semibold text-text-primary">
        {t("imports.reconciliation.history.heading")}
      </h3>
      <Table {...table} caption={t("imports.reconciliation.history.caption")} columns={columns} rows={rows} getRowId={(row) => row.id} />
      <Pagination
        page={pagination.page}
        size={pagination.size}
        total={pagination.total}
        onPageChange={pagination.onPageChange}
        label={t("imports.reconciliation.history.navigation")}
        disabled={pagination.busy}
      />
    </section>
  );
}
