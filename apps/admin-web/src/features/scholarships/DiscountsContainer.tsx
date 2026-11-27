import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { useResourceView } from "../../shared/resource-screen/use-resource-view.ts";
import { DiscountsView } from "./components/DiscountsView.tsx";
import { ApproveDiscountContainer, RejectDiscountContainer } from "./DiscountDecisionContainers.tsx";
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
  const { t } = useAdminText();
  const { can, accountId } = usePermissions();
  const list = useDiscountList(toApiParams(search));
  const save = useDiscountSave();
  const toggle = useDiscountToggle();
  const view = useResourceView<Discount>();
  const mayWrite = can("scholarships:create");
  const mayDecide = can("scholarships:approve");
  // What the person is deciding, as it was when they chose: the answer of the server changes the list under it, but the notice tells what they decided.
  const [deciding, setDeciding] = useState<{ readonly kind: "approve" | "reject"; readonly id: string; readonly name: string; readonly proposal: boolean }>();
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

  // The discount the dialog decides is read again from the list, so a new version of it (another person changed it) is restated, never a stale one.
  const target = deciding === undefined ? undefined : state.rows.find((row) => row.id === deciding.id);
  const decide = (kind: "approve" | "reject") => (row: Discount) => {
    view.clearNotice();
    setDeciding({ kind, id: row.id, name: row.name, proposal: row.pendingChange !== undefined });
  };
  const decided = () => {
    if (deciding === undefined) return;
    setDeciding(undefined);
    const { kind, name, proposal } = deciding;
    if (kind === "approve") view.announce(t(proposal ? "scholarships.discounts.approve.proposalApprovedNotice" : "scholarships.discounts.approve.approvedNotice", { name }));
    else view.announce(t(proposal ? "scholarships.discounts.reject.proposalRejectedNotice" : "scholarships.discounts.reject.rejectedNotice", { name }));
  };

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
        accountId={accountId}
        onApprove={mayDecide ? decide("approve") : undefined}
        onReject={mayDecide ? decide("reject") : undefined}
        onCreate={mayWrite ? view.openCreate : undefined}
        onEdit={mayWrite ? view.openEdit : undefined}
        onToggle={
          mayWrite
            ? (row) => {
                void view.toggle(
                  row.id,
                  row.name,
                  !row.active,
                  () => toggle.mutateAsync({ id: row.id, active: !row.active }),
                  undefined,
                  // Reactivating a campaign discount is only a proposal for another person to approve: the discount stays off until then.
                  (result) => (!row.active && result.pendingChange?.reactivate === true ? t("scholarships.discounts.notices.proposalSent", { name: row.name }) : undefined),
                );
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
            // A campaign discount is born pending, and widening one leaves a proposal: neither applies yet, and the person is told so.
            if (next.mode === "create" && saved.approvalStatus === "pending") {
              view.close();
              view.announce(t("scholarships.discounts.notices.createdPending", { name: saved.name }));
            } else if (saved.pendingChange !== undefined) {
              view.close();
              view.announce(t("scholarships.discounts.notices.proposalSent", { name: saved.name }));
            } else {
              view.saved(next.mode === "create" ? "created" : "updated", saved.name);
            }
          }}
          onClose={view.close}
        />
      )}
      {target === undefined || deciding === undefined ? null : deciding.kind === "approve" ? (
        <ApproveDiscountContainer
          // A new form for each discount, so nothing typed or kept for one is used for another.
          key={target.id}
          discount={target}
          onClose={() => {
            setDeciding(undefined);
          }}
          onDecided={decided}
        />
      ) : (
        <RejectDiscountContainer
          key={target.id}
          discount={target}
          onClose={() => {
            setDeciding(undefined);
          }}
          onDecided={decided}
        />
      )}
    </>
  );
}
