import { Alert, Button, PageHeader, Pagination, Skeleton, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant } from "../../../shared/format/format-date.ts";
import type { ListTableState, ResourcePagination } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { IntegrityRun } from "../charges-types.ts";
import { IntegrityRunStatusBadge } from "./IntegrityRunStatusBadge.tsx";

/** What the banner says about the latest run. */
export type IntegrityBanner =
  | { readonly kind: "loading" }
  /** The check has not run yet for the institution. */
  | { readonly kind: "none" }
  | { readonly kind: "failed"; readonly onRetry: () => void }
  | { readonly kind: "ok"; readonly finishedAt: string; readonly onOpen: () => void }
  | { readonly kind: "discrepancies"; readonly finishedAt: string; readonly count: number; readonly onOpen: () => void };

export interface IntegrityRunsViewProps {
  readonly banner: IntegrityBanner;
  readonly rows: readonly IntegrityRun[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  /** The address of a run, so a link opens in a new tab and a plain click is a navigation of the router. */
  readonly hrefOf: (row: IntegrityRun) => string;
  readonly onOpen: (row: IntegrityRun) => void;
}

function Banner({ banner }: { readonly banner: IntegrityBanner }) {
  const { t } = useAdminText();
  switch (banner.kind) {
    case "loading":
      return (
        <div aria-busy="true">
          <p role="status" className="sr-only">
            {t("charges.integrity.banner.loading")}
          </p>
          <Skeleton className="h-14 w-full" />
        </div>
      );
    case "none":
      return (
        <Alert variant="info">
          <p>{t("charges.integrity.banner.none")}</p>
        </Alert>
      );
    case "failed":
      return (
        <Alert variant="warning">
          <p>{t("charges.integrity.banner.failed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={banner.onRetry}>
            {t("charges.integrity.banner.retry")}
          </Button>
        </Alert>
      );
    case "ok":
      return (
        <Alert variant="info">
          <p>{t("charges.integrity.banner.ok", { date: formatInstant(banner.finishedAt) })}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={banner.onOpen}>
            {t("charges.integrity.banner.viewDetail")}
          </Button>
        </Alert>
      );
    case "discrepancies":
      return (
        <Alert variant="destructive">
          <p>{t("charges.integrity.banner.discrepancies", { date: formatInstant(banner.finishedAt), count: banner.count })}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={banner.onOpen}>
            {t("charges.integrity.banner.viewDetail")}
          </Button>
        </Alert>
      );
  }
}

/** The nightly runs of the integrity check, newest first as the server orders them, under a banner that tells how the latest one went. */
export function IntegrityRunsView({ banner, rows, table, pagination, hrefOf, onOpen }: IntegrityRunsViewProps) {
  const { t } = useAdminText();

  const columns: readonly TableColumn<IntegrityRun>[] = [
    {
      id: "startedAt",
      header: t("charges.integrity.columns.startedAt"),
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
          {formatInstant(row.startedAt)}
        </a>
      ),
    },
    { id: "finishedAt", header: t("charges.integrity.columns.finishedAt"), cell: (row) => formatInstant(row.finishedAt) },
    { id: "status", header: t("charges.integrity.columns.status"), cell: (row) => <IntegrityRunStatusBadge status={row.status} /> },
    { id: "chargesChecked", header: t("charges.integrity.columns.chargesChecked"), align: "end", cell: (row) => String(row.chargesChecked) },
    { id: "postingsChecked", header: t("charges.integrity.columns.postingsChecked"), align: "end", cell: (row) => String(row.postingsChecked) },
    { id: "discrepancyCount", header: t("charges.integrity.columns.discrepancyCount"), align: "end", cell: (row) => String(row.discrepancyCount) },
  ];

  return (
    <section aria-labelledby="integrity-runs-heading" className="flex flex-col gap-3">
      <PageHeader title={t("charges.integrity.heading")} headingLevel={2} headingId="integrity-runs-heading" description={t("charges.integrity.description")} />
      <Banner banner={banner} />
      <Table stickyHeader captionHidden {...table} caption={t("charges.integrity.caption")} columns={columns} rows={rows} getRowId={(row) => row.id} />
      <Pagination page={pagination.page} size={pagination.size} total={pagination.total} onPageChange={pagination.onPageChange} disabled={pagination.busy} />
    </section>
  );
}
