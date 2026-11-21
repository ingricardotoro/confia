import { useNavigate } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { PaymentsView } from "./components/PaymentsView.tsx";
import { paymentPath, PAYMENTS_PATHS } from "./payments-paths.ts";
import type { PaymentsSearch } from "./payments-search.ts";
import { usePaymentList } from "./use-payment-queries.ts";

export interface PaymentsContainerProps {
  readonly search: PaymentsSearch;
  readonly onSearchChange: SearchChange<PaymentsSearch>;
}

/**
 * The payments. The status, the method, the days, the order and the page are in the address, so the view can be shared; the server pages,
 * filters and orders, and the browser never holds more than the page it shows. A cashier sees only their own payments: that is the server rule,
 * and an empty list is the way it answers when there is nothing to see.
 */
export function PaymentsContainer({ search, onSearchChange }: PaymentsContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const list = usePaymentList(toApiParams(search));
  const filtered = search.status !== undefined || search.method !== undefined || search.from !== undefined || search.to !== undefined;
  const periodProblem = search.from !== undefined && search.to !== undefined && search.to < search.from;
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered,
    cleared: { ...search, page: 1, status: undefined, method: undefined, from: undefined, to: undefined },
    // Nothing exists yet: the way forward is to register a payment.
    onCreate: () => void navigate({ to: PAYMENTS_PATHS.register }),
    loadFailedKey: "payments.list.loadFailed",
    emptyKeys: { title: "payments.list.emptyTitle", description: "payments.list.emptyDescription", action: "payments.list.emptyAction" },
    defaultSort: { columnId: "registeredAt", direction: "desc" },
  });

  const change = (next: Partial<PaymentsSearch>) => {
    onSearchChange({ ...search, page: 1, ...next }, { replace: true });
  };

  return (
    <PaymentsView
      filters={{
        status: {
          value: search.status ?? "",
          onChange: (status) => {
            change({ status: status === "declared" || status === "confirmed" || status === "rejected" ? status : undefined });
          },
        },
        method: {
          value: search.method ?? "",
          onChange: (method) => {
            change({ method: method === "CASH" || method === "BANK_TRANSFER" ? method : undefined });
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
          error: periodProblem ? t("payments.list.errors.fromAfterTo") : undefined,
          onChange: (to) => {
            change({ to: to === "" ? undefined : to });
          },
        },
      }}
      rows={state.rows}
      table={state.table}
      pagination={state.pagination}
      hrefOf={(row) => paymentPath(row.id)}
      onOpen={(row) => void navigate({ to: paymentPath(row.id) })}
    />
  );
}
