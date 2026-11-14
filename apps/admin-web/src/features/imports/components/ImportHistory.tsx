import type { ImportBatchResponseOutput } from "@confia/contracts/admin";
import { Badge, Pagination, Table, type TableColumn, type TableProps } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { ResourcePagination } from "../../organization/components/ResourceScreen.tsx";
import { formatInstant } from "../../../shared/format/format-date.ts";

type Batch = ImportBatchResponseOutput;

export interface ImportHistoryProps {
  readonly rows: readonly Batch[];
  /** The state of the load (loading, failed with a retry, empty with a way forward), as the server's answer decides it. */
  readonly table: Pick<TableProps<Batch>, "loading" | "error" | "empty">;
  readonly pagination: ResourcePagination;
}

/**
 * The batches of the institution, newest first, with their counts. A batch holds counts and a hash, never a value of a file,
 * so there is no name here. The pages are the server's; the table only reports the clicks of the pagination.
 */
export function ImportHistory({ rows, table, pagination }: ImportHistoryProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Batch>[] = [
    { id: "createdAt", header: t("imports.history.date"), cell: (row) => formatInstant(row.createdAt) },
    {
      id: "status",
      header: t("imports.history.status"),
      cell: (row) => (
        <Badge variant={row.status === "APPLIED" ? "primary" : "outline"}>
          {row.status === "APPLIED" ? t("imports.history.statusApplied") : t("imports.history.statusValidated")}
        </Badge>
      ),
    },
    { id: "totalRows", header: t("imports.history.total"), align: "end", cell: (row) => row.totalRows },
    { id: "createdRows", header: t("imports.history.created"), align: "end", cell: (row) => row.createdRows },
    { id: "rejectedRows", header: t("imports.history.rejected"), align: "end", cell: (row) => row.rejectedRows },
    { id: "skippedRows", header: t("imports.history.skipped"), align: "end", cell: (row) => row.skippedRows },
  ];

  return (
    <section aria-labelledby="imports-history-heading" className="flex flex-col gap-3">
      <h2 id="imports-history-heading" className="text-xl font-semibold text-text-primary">
        {t("imports.history.heading")}
      </h2>
      <Table {...table} caption={t("imports.history.caption")} columns={columns} rows={rows} getRowId={(row) => row.id} />
      <Pagination page={pagination.page} size={pagination.size} total={pagination.total} onPageChange={pagination.onPageChange} label={t("imports.history.navigation")} disabled={pagination.busy} />
    </section>
  );
}
