import type { TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { OptionField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import {
  ResourceScreen,
  type ListTableState,
  type ResourceNotice,
  type ResourcePagination,
} from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { ChargePlan } from "../charge-plan-form.ts";
import { CHARGE_PLAN_SCOPES, CHARGE_PLAN_STATUSES } from "../charges-search.ts";
import { ChargePlanStatusBadge } from "./ChargePlanStatusBadge.tsx";

export interface ChargePlansViewProps {
  readonly rows: readonly ChargePlan[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** Names the year, the modality, the grade or the section a row points to, by id. A missing id shows "not available". */
  readonly yearNames: ReadonlyMap<string, string>;
  readonly referenceNames: ReadonlyMap<string, string>;
  /** The year options of the filter. */
  readonly yearOptions: readonly Option[];
  /** Set when the lookups of the years, the modalities, the grades or the sections failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The three filters. An empty value means "all". */
  readonly filters: {
    readonly year: { readonly value: string; readonly onChange: (year: string) => void };
    readonly scope: { readonly value: string; readonly onChange: (scope: string) => void };
    readonly status: { readonly value: string; readonly onChange: (status: string) => void };
  };
  /** The address of a plan, so a link opens in a new tab and a plain click is a navigation of the router. */
  readonly hrefOf: (row: ChargePlan) => string;
  readonly onOpen: (row: ChargePlan) => void;
  readonly onCreate: () => void;
}

/** The plans as a table: name (a link to its detail), year, scope, the target it points to, frequency, due day and state. */
export function ChargePlansView({
  rows,
  table,
  pagination,
  notice,
  yearNames,
  referenceNames,
  yearOptions,
  lookupFailure,
  filters,
  hrefOf,
  onOpen,
  onCreate,
}: ChargePlansViewProps) {
  const { t } = useAdminText();
  const unknown = t("resource.unknownReference");
  const scopeOptions: readonly Option[] = CHARGE_PLAN_SCOPES.map((scope) => ({ value: scope, label: t(`charges.plans.scopes.${scope}`) }));
  const statusOptions: readonly Option[] = CHARGE_PLAN_STATUSES.map((status) => ({ value: status, label: t(`charges.plans.statuses.${status}`) }));

  const columns: readonly TableColumn<ChargePlan>[] = [
    {
      id: "name",
      header: t("charges.plans.columns.name"),
      sortable: true,
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
          {row.name}
        </a>
      ),
    },
    { id: "year", header: t("charges.plans.columns.year"), cell: (row) => yearNames.get(row.academicYearId) ?? unknown },
    { id: "scope", header: t("charges.plans.columns.scope"), cell: (row) => t(`charges.plans.scopes.${row.targetScope}`) },
    { id: "reference", header: t("charges.plans.columns.reference"), cell: (row) => referenceNames.get(row.targetRefId) ?? unknown },
    { id: "frequency", header: t("charges.plans.columns.frequency"), cell: (row) => t(`charges.plans.frequencies.${row.frequency}`) },
    { id: "dueDay", header: t("charges.plans.columns.dueDay"), cell: (row) => t("charges.plans.dueDayValue", { day: row.dueDayOfMonth }) },
    { id: "status", header: t("charges.plans.columns.status"), cell: (row) => <ChargePlanStatusBadge status={row.status} /> },
  ];

  return (
    <ResourceScreen
      heading={t("charges.plans.heading")}
      description={t("charges.plans.description")}
      createLabel={t("charges.plans.create")}
      onCreate={onCreate}
      notice={notice}
      lookupFailure={lookupFailure}
      filters={
        <>
          <OptionField
            label={t("charges.plans.filterYear")}
            controlId="charge-plans-filter-year"
            value={filters.year.value}
            options={yearOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.year.onChange}
          />
          <OptionField
            label={t("charges.plans.filterScope")}
            controlId="charge-plans-filter-scope"
            value={filters.scope.value}
            options={scopeOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.scope.onChange}
          />
          <OptionField
            label={t("charges.plans.filterStatus")}
            controlId="charge-plans-filter-status"
            value={filters.status.value}
            options={statusOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.status.onChange}
          />
        </>
      }
      table={{ ...table, caption: t("charges.plans.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
