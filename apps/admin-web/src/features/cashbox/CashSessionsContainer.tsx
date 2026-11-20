import { useNavigate } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { cashSessionPath, CASHBOX_PATHS } from "./cashbox-paths.ts";
import type { CashSessionsSearch } from "./cashbox-search.ts";
import { CashSessionsView } from "./components/CashSessionsView.tsx";
import { useCashSessionList } from "./use-cashbox-queries.ts";

export interface CashSessionsContainerProps {
  readonly search: CashSessionsSearch;
  readonly onSearchChange: SearchChange<CashSessionsSearch>;
}

/**
 * The sessions of the cashbox. The status, the days, the cashier, the order and the page are in the address, so the view can be shared; the
 * server pages, filters and orders, and the browser never holds more than the page it shows. A cashier sees only their own sessions: that is
 * the server's rule, and an empty list is the way it answers when there is nothing to see.
 */
export function CashSessionsContainer({ search, onSearchChange }: CashSessionsContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const list = useCashSessionList(toApiParams(search));
  const filtered = search.status !== undefined || search.cashier !== undefined || search.from !== undefined || search.to !== undefined;
  const periodProblem = search.from !== undefined && search.to !== undefined && search.to < search.from;
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered,
    cleared: { ...search, page: 1, status: undefined, cashier: undefined, from: undefined, to: undefined },
    // Nothing exists yet and nothing can be created from here: the way forward is the cashier's own cashbox, where a session is opened.
    onCreate: () => void navigate({ to: CASHBOX_PATHS.mine }),
    loadFailedKey: "cashbox.sessions.loadFailed",
    emptyKeys: { title: "cashbox.sessions.emptyTitle", description: "cashbox.sessions.emptyDescription", action: "cashbox.sessions.emptyAction" },
    defaultSort: { columnId: "openedAt", direction: "desc" },
  });

  const change = (next: Partial<CashSessionsSearch>) => {
    onSearchChange({ ...search, page: 1, ...next }, { replace: true });
  };

  return (
    <CashSessionsView
      filters={{
        status: {
          value: search.status ?? "",
          onChange: (status) => {
            change({ status: status === "open" || status === "pending_approval" || status === "closed" ? status : undefined });
          },
        },
        from: {
          value: search.from ?? "",
          onChange: (from) => {
            change({ from: from === "" ? undefined : from });
          },
        },
        to: {
          value: search.to ?? "",
          error: periodProblem ? t("cashbox.sessions.errors.fromAfterTo") : undefined,
          onChange: (to) => {
            change({ to: to === "" ? undefined : to });
          },
        },
      }}
      rows={state.rows}
      table={state.table}
      pagination={state.pagination}
      hrefOf={(row) => cashSessionPath(row.id)}
      onOpen={(row) => void navigate({ to: cashSessionPath(row.id) })}
    />
  );
}
