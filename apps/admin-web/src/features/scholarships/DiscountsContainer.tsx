import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { useResourceView } from "../../shared/resource-screen/use-resource-view.ts";
import { DiscountsView } from "./components/DiscountsView.tsx";
import { DiscountFormContainer } from "./DiscountFormContainer.tsx";
import { DISCOUNT_KINDS, type DiscountsSearch } from "./discounts-search.ts";
import type { Discount } from "./discounts-types.ts";
import { BENEFIT_TYPES } from "./scholarships-search.ts";
import { useDiscountList, useDiscountSave, useDiscountToggle } from "./use-discount-queries.ts";

export interface DiscountsContainerProps {
  readonly search: DiscountsSearch;
  readonly onSearchChange: SearchChange<DiscountsSearch>;
}

/**
 * The discounts: the list as the server serves it (filtered, ordered and paged there), the form in a dialog, and activate or deactivate from the
 * row. There is no delete: a discount that is no longer offered is deactivated, and it stops applying to the charges to come (the issued ones do not
 * change). The class, the type, the state, the order and the page are in the address, so the view can be shared.
 */
export function DiscountsContainer({ search, onSearchChange }: DiscountsContainerProps) {
  const { can } = usePermissions();
  const list = useDiscountList(toApiParams(search));
  const save = useDiscountSave();
  const toggle = useDiscountToggle();
  const view = useResourceView<Discount>();
  const mayWrite = can("scholarships:create");
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.kind !== undefined || search.benefitType !== undefined || search.active !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, kind: undefined, benefitType: undefined, active: undefined },
    onCreate: mayWrite ? view.openCreate : undefined,
    loadFailedKey: "scholarships.discounts.loadFailed",
    emptyKeys: {
      title: "scholarships.discounts.emptyTitle",
      description: "scholarships.discounts.emptyDescription",
      action: "scholarships.discounts.emptyAction",
    },
    defaultSort: { columnId: "code", direction: "asc" },
  });

  return (
    <>
      <DiscountsView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        togglingId={view.togglingId}
        filters={{
          kind: {
            value: search.kind ?? "",
            onChange: (kind) => {
              onSearchChange({ ...search, kind: DISCOUNT_KINDS.find((known) => known === kind), page: 1 }, { replace: true });
            },
          },
          benefitType: {
            value: search.benefitType ?? "",
            onChange: (benefitType) => {
              onSearchChange({ ...search, benefitType: BENEFIT_TYPES.find((known) => known === benefitType), page: 1 }, { replace: true });
            },
          },
          active: {
            value: search.active === undefined ? "" : String(search.active),
            onChange: (active) => {
              onSearchChange({ ...search, active: active === "" ? undefined : active === "true", page: 1 }, { replace: true });
            },
          },
        }}
        onCreate={mayWrite ? view.openCreate : undefined}
        onEdit={mayWrite ? view.openEdit : undefined}
        onToggle={
          mayWrite
            ? (row) => {
                void view.toggle(row.id, row.name, !row.active, () => toggle.mutateAsync({ id: row.id, active: !row.active }));
              }
            : undefined
        }
      />
      {view.dialog.kind === "closed" ? null : (
        <DiscountFormContainer
          // A new form for each record, so nothing typed for one is shown for another.
          key={view.dialog.kind === "edit" ? view.dialog.row.id : "create"}
          {...(view.dialog.kind === "edit" ? { row: view.dialog.row } : {})}
          save={async (next) => {
            const saved = await save.mutateAsync(next);
            view.saved(next.mode === "create" ? "created" : "updated", saved.name);
          }}
          onClose={view.close}
        />
      )}
    </>
  );
}
