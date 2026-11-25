import { Alert, Button, PageHeader, Pagination, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant, formatIsoDate } from "../../../shared/format/format-date.ts";
import { OptionField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import type { ListTableState, ResourceNotice, ResourcePagination } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { StatementStudentPicker, type StatementStudentPickerProps } from "../../../shared/student-lookups/components/StatementStudentPicker.tsx";
import { AWARD_STATUSES } from "../scholarships-search.ts";
import { scholarshipLabelOf, type Award } from "../scholarships-types.ts";
import { AwardStatusBadge } from "./AwardStatusBadge.tsx";

export interface AwardsViewProps {
  readonly filters: {
    readonly status: { readonly value: string; readonly onChange: (status: string) => void };
    readonly scholarship: { readonly value: string; readonly options: readonly Option[]; readonly onChange: (scholarshipId: string) => void };
    /** The student: the search box while none is chosen, and the chosen one (with how to change it) afterwards. */
    readonly student: {
      readonly picker: StatementStudentPickerProps;
      readonly chosen?: { readonly label: string; readonly onClear: () => void } | undefined;
    };
  };
  /** Set when the scholarships failed to load, so the filter has no options. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly rows: readonly Award[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  /** The result of the last action, already translated. */
  readonly notice?: ResourceNotice | undefined;
  /** The address of an award, so a link opens in a new tab and a plain click is a navigation of the router. */
  readonly hrefOf: (row: Award) => string;
  readonly onOpen: (row: Award) => void;
  /** Absent when the person cannot request an award: the button is not drawn. */
  readonly onRequest?: (() => void) | undefined;
}

/**
 * The awards as the server pages, filters and orders them: when each was requested, for which student (their code), which scholarship, the validity
 * and the state. The filters are the status, the scholarship and the student. A row opens its detail, where a pending award is decided.
 */
export function AwardsView({ filters, lookupFailure, rows, table, pagination, notice, hrefOf, onOpen, onRequest }: AwardsViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Award>[] = [
    {
      id: "requestedAt",
      header: t("scholarships.awards.columns.requestedAt"),
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
          {formatInstant(row.requestedAt)}
        </a>
      ),
    },
    { id: "student", header: t("scholarships.awards.columns.student"), cell: (row) => row.studentCode ?? t("scholarships.awards.noValue") },
    { id: "scholarship", header: t("scholarships.awards.columns.scholarship"), cell: (row) => scholarshipLabelOf(row) ?? t("scholarships.awards.noValue") },
    { id: "validFrom", header: t("scholarships.awards.columns.validFrom"), sortable: true, cell: (row) => <span className="tabular-nums">{formatIsoDate(row.validFrom)}</span> },
    { id: "validTo", header: t("scholarships.awards.columns.validTo"), sortable: true, cell: (row) => <span className="tabular-nums">{formatIsoDate(row.validTo)}</span> },
    { id: "status", header: t("scholarships.awards.columns.status"), cell: (row) => <AwardStatusBadge status={row.status} /> },
  ];

  return (
    <section aria-labelledby="awards-heading" className="flex flex-col gap-3">
      <PageHeader
        title={t("scholarships.awards.heading")}
        headingLevel={2}
        headingId="awards-heading"
        description={t("scholarships.awards.description")}
        {...(onRequest === undefined
          ? {}
          : {
              actions: (
                <Button size="sm" onClick={onRequest}>
                  {t("scholarships.awards.request")}
                </Button>
              ),
            })}
      />
      <div role="group" aria-label={t("resource.filters")} className="flex flex-wrap items-end gap-x-3 gap-y-2 rounded-lg border border-border-subtle bg-bg-surface px-3 py-2">
        <OptionField
          label={t("scholarships.awards.filterStatus")}
          controlId="awards-status"
          value={filters.status.value}
          placeholder={t("resource.allOption")}
          options={AWARD_STATUSES.map((status) => ({ value: status, label: t(`scholarships.statuses.${status}`) }))}
          onChange={filters.status.onChange}
        />
        <OptionField
          label={t("scholarships.awards.filterScholarship")}
          controlId="awards-scholarship"
          value={filters.scholarship.value}
          placeholder={t("resource.allOption")}
          options={filters.scholarship.options}
          onChange={filters.scholarship.onChange}
        />
        {filters.student.chosen === undefined ? (
          <StatementStudentPicker {...filters.student.picker} />
        ) : (
          <div role="group" aria-label={t("charges.statement.picker.chosen")} className="flex flex-col gap-1.5">
            <p className="text-sm font-medium text-text-primary">{t("charges.statement.picker.label")}</p>
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border-subtle px-3 py-2">
              <p className="text-sm font-medium text-text-primary">{filters.student.chosen.label}</p>
              <Button variant="secondary" size="sm" onClick={filters.student.chosen.onClear}>
                {t("scholarships.awards.clearStudent")}
              </Button>
            </div>
          </div>
        )}
      </div>
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("resource.optionsFailed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      <div aria-live="polite" aria-atomic="true" className="empty:hidden">
        {notice === undefined ? null : (
          <Alert variant={notice.tone === "error" ? "destructive" : "info"}>
            <p>{notice.message}</p>
            {notice.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: notice.traceId })}</p>}
          </Alert>
        )}
      </div>
      <Table stickyHeader captionHidden {...table} caption={t("scholarships.awards.caption")} columns={columns} rows={rows} getRowId={(row) => row.id} />
      <Pagination page={pagination.page} size={pagination.size} total={pagination.total} onPageChange={pagination.onPageChange} disabled={pagination.busy} />
    </section>
  );
}
