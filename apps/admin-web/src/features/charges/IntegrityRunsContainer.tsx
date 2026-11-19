import { useNavigate } from "@tanstack/react-router";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { IntegrityRunsView, type IntegrityBanner } from "./components/IntegrityRunsView.tsx";
import { integrityRunPath } from "./charges-paths.ts";
import type { IntegrityRunsSearch } from "./charges-search.ts";
import { useIntegrityRunList, useLatestIntegrityRun } from "./use-integrity-queries.ts";

export interface IntegrityRunsContainerProps {
  readonly search: IntegrityRunsSearch;
  readonly onSearchChange: SearchChange<IntegrityRunsSearch>;
}

/**
 * The nightly runs of the integrity check: the list as the server serves it (newest first, paged there, the page in the address),
 * and a banner that says how the latest run went, read on its own so it does not depend on the page that is open. A run that
 * has not happened yet is a state, not a failure. Nothing here repairs, computes or compares an amount.
 */
export function IntegrityRunsContainer({ search, onSearchChange }: IntegrityRunsContainerProps) {
  const navigate = useNavigate();
  const list = useIntegrityRunList(toApiParams(search));
  const latest = useLatestIntegrityRun();
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: false,
    cleared: { ...search, page: 1 },
    // Nothing exists yet and nothing can be created from here: the way forward is to read again.
    onCreate: () => void list.refetch(),
    loadFailedKey: "charges.integrity.loadFailed",
    emptyKeys: { title: "charges.integrity.emptyTitle", description: "charges.integrity.emptyDescription", action: "charges.integrity.emptyAction" },
  });

  function bannerOf(): IntegrityBanner {
    const data = latest.data;
    if (data !== undefined) {
      const open = () => void navigate({ to: integrityRunPath(data.run.id) });
      return data.run.status === "OK"
        ? { kind: "ok", finishedAt: data.run.finishedAt, onOpen: open }
        : { kind: "discrepancies", finishedAt: data.run.finishedAt, count: data.run.discrepancyCount, onOpen: open };
    }
    if (latest.isError) {
      const notYet = latest.error instanceof ApiFailure && latest.error.result.kind === "problem" && latest.error.result.problem.status === 404;
      return notYet ? { kind: "none" } : { kind: "failed", onRetry: () => void latest.refetch() };
    }
    return { kind: "loading" };
  }

  return (
    <IntegrityRunsView
      banner={bannerOf()}
      rows={state.rows}
      table={state.table}
      pagination={state.pagination}
      hrefOf={(row) => integrityRunPath(row.id)}
      onOpen={(row) => void navigate({ to: integrityRunPath(row.id) })}
    />
  );
}
