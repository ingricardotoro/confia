import { useNavigate } from "@tanstack/react-router";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { useResourceView } from "../../shared/resource-screen/use-resource-view.ts";
import { taxRatePath } from "./catalog-paths.ts";
import type { TaxRatesSearch } from "./catalog-search.ts";
import { TaxRatesView } from "./components/TaxRatesView.tsx";
import { stateBodyOf, type TaxRate } from "./tax-rate-form.ts";
import { TaxRateFormContainer } from "./TaxRateFormContainer.tsx";
import { useTaxRateList, useTaxRateWrite } from "./use-catalog-queries.ts";

/** The `active` filter as the API takes it: only strings travel in a query string. */
function activeParamOf(active: boolean | undefined): "true" | "false" | undefined {
  if (active === undefined) return undefined;
  return active ? "true" : "false";
}

export interface TaxRatesContainerProps {
  readonly search: TaxRatesSearch;
  readonly onSearchChange: SearchChange<TaxRatesSearch>;
}

/**
 * The tax rates: the list as the server serves it (filtered, ordered and paged there), the form in a dialog, a link
 * from each row to its detail, and activate or deactivate from the row. There is no delete: a rate that is not used any
 * more is deactivated, and its percentages are validities that never change.
 */
export function TaxRatesContainer({ search, onSearchChange }: TaxRatesContainerProps) {
  const { can } = usePermissions();
  const navigate = useNavigate();
  const list = useTaxRateList({ ...toApiParams(search), active: activeParamOf(search.active) });
  const write = useTaxRateWrite();
  const view = useResourceView<TaxRate>();
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.active !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, active: undefined },
    onCreate: can("catalog:create") ? view.openCreate : undefined,
    loadFailedKey: "catalog.taxRates.loadFailed",
    emptyKeys: {
      title: "catalog.taxRates.emptyTitle",
      description: "catalog.taxRates.emptyDescription",
      action: "catalog.taxRates.emptyAction",
    },
    defaultSort: { columnId: "code", direction: "asc" },
  });

  return (
    <>
      <TaxRatesView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        togglingId={view.togglingId}
        filters={{
          active: {
            value: search.active === undefined ? "" : String(search.active),
            onChange: (active) => {
              onSearchChange({ ...search, active: active === "" ? undefined : active === "true", page: 1 }, { replace: true });
            },
          },
        }}
        hrefOf={(row) => taxRatePath(row.id)}
        onOpen={(row) => void navigate({ to: taxRatePath(row.id) })}
        onCreate={can("catalog:create") ? view.openCreate : undefined}
        onEdit={can("catalog:update") ? view.openEdit : undefined}
        onToggle={can("catalog:update") ? (row) => {
          void view.toggle(row.id, row.name, !row.isActive, () =>
            write.mutateAsync({ mode: "update", id: row.id, body: stateBodyOf(row, !row.isActive) }),
          );
        } : undefined}
      />
      {view.dialog.kind === "closed" ? null : (
        <TaxRateFormContainer
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
