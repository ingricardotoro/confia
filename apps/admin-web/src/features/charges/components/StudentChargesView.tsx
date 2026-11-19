import { Alert, Button, EmptyState, MoneyAmount, PageHeader, Pagination, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant, formatIsoDate } from "../../../shared/format/format-date.ts";
import { OptionField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import type { ListTableState, ResourceNotice, ResourcePagination } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { StudentCharge } from "../charges-types.ts";
import { ChargeStatusBadge } from "./ChargeStatusBadge.tsx";
import { StatementStudentPicker, type StatementStudentPickerProps } from "./StatementStudentPicker.tsx";

export type StudentChargesState =
  /** No student is chosen: the page says how to start. */
  | { readonly kind: "idle" }
  /** The period the person typed ends before it starts: nothing is asked of the server. */
  | { readonly kind: "invalid-period" }
  /** The answer was no and will stay no: a student that is not in this institution (404) or a missing permission (403). */
  | { readonly kind: "blocked"; readonly title: string; readonly description: string }
  /** A student is chosen: the table shows its own loading, error, empty and rows. */
  | { readonly kind: "ready" };

export interface StudentChargesViewProps {
  readonly state: StudentChargesState;
  readonly picker: StatementStudentPickerProps;
  /** Present while a student is chosen: how to name them (the address only holds the id) and the two ways out. */
  readonly chosen?: { readonly label: string | undefined; readonly onChange: () => void; readonly onViewStatement: () => void } | undefined;
  readonly filters: {
    readonly status: { readonly value: string; readonly onChange: (status: string) => void };
    readonly from: { readonly value: string; readonly onChange: (from: string) => void };
    readonly to: { readonly value: string; readonly error?: string | undefined; readonly onChange: (to: string) => void };
  };
  readonly rows: readonly StudentCharge[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  /** The result of the last void, already translated. */
  readonly notice?: ResourceNotice | undefined;
  readonly onVoid: (row: StudentCharge) => void;
}

const focusField = (id: string) => () => {
  document.getElementById(id)?.focus();
};

/**
 * The charges of a student, one per row, with the status the server derives, the amounts as the server rounded them and, on every
 * charge that is not voided, the action to void it. It renders what it is given and asks for nothing: paging, order and filters are
 * the server's, and nothing here adds, subtracts or rounds an amount. The session carries no permissions, so the action is offered on
 * every charge in force and the server's refusal (403) comes back as a message in the dialog.
 */
export function StudentChargesView({ state, picker, chosen, filters, rows, table, pagination, notice, onVoid }: StudentChargesViewProps) {
  const { t } = useAdminText();
  const statusOptions: readonly Option[] = [
    { value: "ACTIVE", label: t("charges.studentCharges.statuses.ACTIVE") },
    { value: "VOIDED", label: t("charges.studentCharges.statuses.VOIDED") },
  ];
  const conceptOf = (row: StudentCharge): string => row.conceptName ?? row.conceptCode ?? t("charges.studentCharges.noConcept");

  const columns: readonly TableColumn<StudentCharge>[] = [
    { id: "periodStart", header: t("charges.studentCharges.columns.period"), sortable: true, cell: (row) => row.periodLabel },
    { id: "concept", header: t("charges.studentCharges.columns.concept"), cell: conceptOf },
    { id: "dueOn", header: t("charges.studentCharges.columns.dueOn"), sortable: true, cell: (row) => formatIsoDate(row.dueOn) },
    { id: "base", header: t("charges.studentCharges.columns.base"), align: "end", cell: (row) => <MoneyAmount value={row.base} size="sm" /> },
    { id: "tax", header: t("charges.studentCharges.columns.tax"), align: "end", cell: (row) => <MoneyAmount value={row.tax} size="sm" /> },
    { id: "total", header: t("charges.studentCharges.columns.total"), sortable: true, align: "end", cell: (row) => <MoneyAmount value={row.total} size="sm" emphasis="strong" /> },
    {
      id: "status",
      header: t("charges.studentCharges.columns.status"),
      cell: (row) => (
        <span className="flex flex-col items-start gap-0.5">
          <ChargeStatusBadge status={row.status} />
          {row.voided === undefined ? null : <span className="text-xs text-text-secondary">{t("charges.studentCharges.voidedOn", { date: formatInstant(row.voided.voidedAt) })}</span>}
        </span>
      ),
    },
    {
      id: "actions",
      header: t("charges.studentCharges.columns.actions"),
      cell: (row) =>
        row.status === "ACTIVE" ? (
          <Button
            variant="destructive-soft"
            size="xs"
            aria-label={t("charges.studentCharges.voidActionFor", { concept: conceptOf(row), period: row.periodLabel })}
            onClick={() => {
              onVoid(row);
            }}
          >
            {t("charges.studentCharges.voidAction")}
          </Button>
        ) : null,
    },
  ];

  return (
    <section aria-labelledby="student-charges-heading" className="flex flex-col gap-3">
      <PageHeader title={t("charges.studentCharges.heading")} headingLevel={2} headingId="student-charges-heading" description={t("charges.studentCharges.description")} />
      <div className="flex flex-col gap-3 rounded-lg border border-border-subtle p-3">
        {chosen === undefined ? (
          <StatementStudentPicker {...picker} />
        ) : (
          <div role="group" aria-label={t("charges.statement.picker.chosen")} className="flex flex-col gap-1.5">
            <p className="text-sm font-medium text-text-primary">{t("charges.statement.picker.label")}</p>
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border-subtle px-3 py-2">
              <p className="text-sm font-medium text-text-primary">{chosen.label ?? t("charges.studentCharges.studentUnnamed")}</p>
              <span className="flex flex-wrap gap-2">
                <Button variant="secondary" size="sm" onClick={chosen.onViewStatement}>
                  {t("charges.studentCharges.viewStatement")}
                </Button>
                <Button variant="secondary" size="sm" onClick={chosen.onChange}>
                  {t("charges.studentCharges.changeStudent")}
                </Button>
              </span>
            </div>
          </div>
        )}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <OptionField
            label={t("charges.studentCharges.filterStatus")}
            controlId="student-charges-status"
            value={filters.status.value}
            options={statusOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.status.onChange}
          />
          <TextField label={t("charges.studentCharges.filterFrom")} controlId="student-charges-from" kind="date" value={filters.from.value} onChange={filters.from.onChange} onBlur={() => undefined} />
          <TextField
            label={t("charges.studentCharges.filterTo")}
            controlId="student-charges-to"
            kind="date"
            value={filters.to.value}
            error={filters.to.error}
            onChange={filters.to.onChange}
            onBlur={() => undefined}
          />
        </div>
        <p className="text-sm text-text-secondary">{t("charges.studentCharges.periodHelp")}</p>
      </div>
      <div aria-live="polite" aria-atomic="true" className="empty:-mt-3">
        {notice === undefined ? null : (
          <Alert variant={notice.tone === "error" ? "destructive" : "info"}>
            <p>{notice.message}</p>
            {notice.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: notice.traceId })}</p>}
          </Alert>
        )}
      </div>
      {state.kind === "idle" ? (
        <EmptyState
          title={t("charges.studentCharges.idleTitle")}
          description={t("charges.studentCharges.idleDescription")}
          action={{ label: t("charges.studentCharges.idleAction"), onAction: focusField(picker.controlId) }}
        />
      ) : state.kind === "invalid-period" ? (
        <EmptyState
          title={t("charges.studentCharges.invalidPeriodTitle")}
          description={t("charges.studentCharges.invalidPeriodDescription")}
          action={{ label: t("charges.studentCharges.invalidPeriodAction"), onAction: focusField("student-charges-to") }}
        />
      ) : state.kind === "blocked" ? (
        <Alert variant="warning">
          <p className="font-medium">{state.title}</p>
          <p className="mt-1">{state.description}</p>
        </Alert>
      ) : (
        <>
          <Table stickyHeader captionHidden {...table} caption={t("charges.studentCharges.caption")} columns={columns} rows={rows} getRowId={(row) => row.id} />
          <Pagination page={pagination.page} size={pagination.size} total={pagination.total} onPageChange={pagination.onPageChange} disabled={pagination.busy} />
        </>
      )}
    </section>
  );
}
