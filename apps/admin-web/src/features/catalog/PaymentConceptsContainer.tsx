import { toApiParams } from "../organization/list-search.ts";
import type { SearchChange } from "../organization/search-change.ts";
import { useListState } from "../organization/use-list-state.ts";
import { useResourceView } from "../organization/use-resource-view.ts";
import { PAYMENT_CATEGORIES, type PaymentConceptsSearch } from "./catalog-search.ts";
import { PaymentConceptsView } from "./components/PaymentConceptsView.tsx";
import { PaymentConceptFormContainer } from "./PaymentConceptFormContainer.tsx";
import { updateBodyOf, type PaymentConcept } from "./payment-concept-form.ts";
import { usePaymentConceptList, usePaymentConceptWrite } from "./use-catalog-queries.ts";

export interface PaymentConceptsContainerProps {
  readonly search: PaymentConceptsSearch;
  readonly onSearchChange: SearchChange<PaymentConceptsSearch>;
}

/**
 * The payment concepts: the list as the server serves it (filtered, ordered and paged there), the form in a dialog,
 * and activate or deactivate from the row. There is no delete: a concept that is not offered any more is deactivated.
 */
export function PaymentConceptsContainer({ search, onSearchChange }: PaymentConceptsContainerProps) {
  const list = usePaymentConceptList({ ...toApiParams(search), active: search.active === undefined ? undefined : search.active ? "true" : "false" });
  const write = usePaymentConceptWrite();
  const view = useResourceView<PaymentConcept>();
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.category !== undefined || search.active !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, category: undefined, active: undefined },
    onCreate: view.openCreate,
    loadFailedKey: "catalog.paymentConcepts.loadFailed",
    emptyKeys: {
      title: "catalog.paymentConcepts.emptyTitle",
      description: "catalog.paymentConcepts.emptyDescription",
      action: "catalog.paymentConcepts.emptyAction",
    },
    defaultSort: { columnId: "code", direction: "asc" },
  });

  return (
    <>
      <PaymentConceptsView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        togglingId={view.togglingId}
        filters={{
          category: {
            value: search.category ?? "",
            onChange: (category) => {
              onSearchChange({ ...search, category: PAYMENT_CATEGORIES.find((known) => known === category), page: 1 }, { replace: true });
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
          void view.toggle(row.id, row.name, !row.isActive, () => write.mutateAsync({ mode: "update", id: row.id, body: updateBodyOf(row, !row.isActive) }));
        }}
      />
      {view.dialog.kind === "closed" ? null : (
        <PaymentConceptFormContainer
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
