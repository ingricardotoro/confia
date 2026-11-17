import type { TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { AccrualRun } from "../../../shared/api/charges-operations.ts";
import { formatInstant } from "../../../shared/format/format-date.ts";
import { OptionField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import {
  ResourceScreen,
  type ListTableState,
  type ResourceNotice,
  type ResourcePagination,
} from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { periodLabelOf } from "../accrual-model.ts";
import { AccrualRunStatusBadge } from "./AccrualRunStatusBadge.tsx";

export interface AccrualRunsViewProps {
  readonly rows: readonly AccrualRun[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** Names the plan of a run, by id. A missing id shows "not available". */
  readonly planNames: ReadonlyMap<string, string>;
  readonly planOptions: readonly Option[];
  /** Set when the list of plans (the names and the options of the filter) failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly filters: { readonly plan: { readonly value: string; readonly onChange: (plan: string) => void } };
  /** The address of a run, so a link opens in a new tab and a plain click is a navigation of the router. */
  readonly hrefOf: (row: AccrualRun) => string;
  readonly onOpen: (row: AccrualRun) => void;
  /** The way to the generation page. */
  readonly onCreate: () => void;
}

/** The runs as a table, newest first as the server orders them: period (a link to the run), plan, state, the four counters and the dates. */
export function AccrualRunsView({ rows, table, pagination, notice, planNames, planOptions, lookupFailure, filters, hrefOf, onOpen, onCreate }: AccrualRunsViewProps) {
  const { t } = useAdminText();
  const unknown = t("resource.unknownReference");

  const columns: readonly TableColumn<AccrualRun>[] = [
    {
      id: "period",
      header: t("charges.runs.columns.period"),
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
          {periodLabelOf(row.periodStart)}
        </a>
      ),
    },
    { id: "plan", header: t("charges.runs.columns.plan"), cell: (row) => planNames.get(row.planId) ?? unknown },
    { id: "status", header: t("charges.runs.columns.status"), cell: (row) => <AccrualRunStatusBadge status={row.status} /> },
    { id: "studentsEvaluated", header: t("charges.runs.columns.studentsEvaluated"), align: "end", cell: (row) => String(row.studentsEvaluated) },
    { id: "chargesCreated", header: t("charges.runs.columns.chargesCreated"), align: "end", cell: (row) => String(row.chargesCreated) },
    { id: "chargesExisting", header: t("charges.runs.columns.chargesExisting"), align: "end", cell: (row) => String(row.chargesExisting) },
    { id: "chargesFailed", header: t("charges.runs.columns.chargesFailed"), align: "end", cell: (row) => String(row.chargesFailed) },
    { id: "queuedAt", header: t("charges.runs.columns.queuedAt"), cell: (row) => formatInstant(row.queuedAt) },
    { id: "finishedAt", header: t("charges.runs.columns.finishedAt"), cell: (row) => (row.finishedAt == null ? t("charges.runs.notYet") : formatInstant(row.finishedAt)) },
  ];

  return (
    <ResourceScreen
      heading={t("charges.runs.heading")}
      description={t("charges.runs.description")}
      createLabel={t("charges.runs.create")}
      onCreate={onCreate}
      notice={notice}
      lookupFailure={lookupFailure}
      filters={
        <OptionField
          label={t("charges.runs.filterPlan")}
          controlId="accrual-runs-filter-plan"
          value={filters.plan.value}
          options={planOptions}
          placeholder={t("resource.allOption")}
          onChange={filters.plan.onChange}
        />
      }
      table={{ ...table, caption: t("charges.runs.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
