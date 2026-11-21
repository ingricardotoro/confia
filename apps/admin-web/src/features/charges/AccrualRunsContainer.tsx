import { useNavigate } from "@tanstack/react-router";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import { LOOKUP_PAGE_SIZE } from "../../shared/organization-lookups/use-organization-queries.ts";
import { nameMapOf, optionsOf } from "../../shared/organization-lookups/use-lookups.ts";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { AccrualRunsView } from "./components/AccrualRunsView.tsx";
import { accrualRunPath, CHARGES_PATHS } from "./charges-paths.ts";
import type { AccrualRunsSearch } from "./charges-search.ts";
import { useAccrualRunList } from "./use-accrual-queries.ts";
import { useChargePlanList } from "./use-charges-queries.ts";

export interface AccrualRunsContainerProps {
  readonly search: AccrualRunsSearch;
  readonly onSearchChange: SearchChange<AccrualRunsSearch>;
}

/**
 * The accrual runs: the list as the server serves it (newest first, paged and filtered by plan there, with the page and the
 * filter in the address), and a link from each row to the run. A list that holds a run that has not finished reads itself again.
 */
export function AccrualRunsContainer({ search, onSearchChange }: AccrualRunsContainerProps) {
  const { can } = usePermissions();
  const navigate = useNavigate();
  const list = useAccrualRunList(toApiParams(search));
  const plans = useChargePlanList({ page: 0, size: LOOKUP_PAGE_SIZE, sort: "name,asc" });
  const planRows = plans.data?.items ?? [];
  const goToGeneration = () => void navigate({ to: CHARGES_PATHS.accrual });
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.planId !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, planId: undefined },
    onCreate: can("charges:create") ? goToGeneration : undefined,
    loadFailedKey: "charges.runs.loadFailed",
    emptyKeys: { title: "charges.runs.emptyTitle", description: "charges.runs.emptyDescription", action: "charges.runs.emptyAction" },
  });

  return (
    <AccrualRunsView
      rows={state.rows}
      table={state.table}
      pagination={state.pagination}
      planNames={nameMapOf(planRows)}
      planOptions={optionsOf(planRows)}
      lookupFailure={plans.isError ? { onRetry: () => void plans.refetch() } : undefined}
      filters={{
        plan: {
          value: search.planId ?? "",
          onChange: (plan) => {
            onSearchChange({ ...search, planId: planRows.find((known) => known.id === plan)?.id, page: 1 }, { replace: true });
          },
        },
      }}
      hrefOf={(row) => accrualRunPath(row.id)}
      onOpen={(row) => void navigate({ to: accrualRunPath(row.id) })}
      onCreate={can("charges:create") ? goToGeneration : undefined}
    />
  );
}
