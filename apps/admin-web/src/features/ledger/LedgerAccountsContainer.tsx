import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { useResourceView } from "../../shared/resource-screen/use-resource-view.ts";
import { updateBodyOf, type LedgerAccount } from "./account-form.ts";
import { LedgerAccountsView } from "./components/LedgerAccountsView.tsx";
import { LedgerAccountFormContainer } from "./LedgerAccountFormContainer.tsx";
import { ACCOUNT_TYPES, type LedgerAccountsSearch } from "./ledger-search.ts";
import { useLedgerAccountList, useLedgerAccountWrite } from "./use-ledger-queries.ts";

/** The `active` filter as the API takes it: only strings travel in a query string. */
function activeParamOf(active: boolean | undefined): "true" | "false" | undefined {
  if (active === undefined) return undefined;
  return active ? "true" : "false";
}

export interface LedgerAccountsContainerProps {
  readonly search: LedgerAccountsSearch;
  readonly onSearchChange: SearchChange<LedgerAccountsSearch>;
}

/**
 * The chart of accounts: the list as the server serves it (filtered, ordered and paged there), the form in a dialog, and
 * activate or deactivate from the row. There is no delete: an account that is not used any more is deactivated, and the
 * server refuses to deactivate one the map of posting accounts still points at.
 */
export function LedgerAccountsContainer({ search, onSearchChange }: LedgerAccountsContainerProps) {
  const list = useLedgerAccountList({ ...toApiParams(search), active: activeParamOf(search.active) });
  const write = useLedgerAccountWrite();
  const view = useResourceView<LedgerAccount>();
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.type !== undefined || search.active !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, type: undefined, active: undefined },
    onCreate: view.openCreate,
    loadFailedKey: "ledger.accounts.loadFailed",
    emptyKeys: {
      title: "ledger.accounts.emptyTitle",
      description: "ledger.accounts.emptyDescription",
      action: "ledger.accounts.emptyAction",
    },
    defaultSort: { columnId: "code", direction: "asc" },
  });

  return (
    <>
      <LedgerAccountsView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        togglingId={view.togglingId}
        filters={{
          type: {
            value: search.type ?? "",
            onChange: (type) => {
              onSearchChange({ ...search, type: ACCOUNT_TYPES.find((known) => known === type), page: 1 }, { replace: true });
            },
          },
          active: {
            value: search.active === undefined ? "" : String(search.active),
            onChange: (active) => {
              onSearchChange({ ...search, active: active === "" ? undefined : active === "true", page: 1 }, { replace: true });
            },
          },
        }}
        onCreate={view.openCreate}
        onEdit={view.openEdit}
        onToggle={(row) => {
          void view.toggle(row.id, row.name, !row.active, () => write.mutateAsync({ mode: "update", id: row.id, body: updateBodyOf(row, !row.active) }));
        }}
      />
      {view.dialog.kind === "closed" ? null : (
        <LedgerAccountFormContainer
          // A new form for each record, so nothing typed for one is shown for another.
          key={view.dialog.kind === "edit" ? view.dialog.row.id : "create"}
          {...(view.dialog.kind === "edit" ? { row: view.dialog.row } : {})}
          save={async (save) => {
            const saved = await write.mutateAsync(save);
            view.saved(save.mode === "create" ? "created" : "updated", saved.name);
          }}
          onClose={view.close}
        />
      )}
    </>
  );
}
