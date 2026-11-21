import { createRoute, stripSearchParams, useParams, type AnyRoute } from "@tanstack/react-router";
import { z } from "zod";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../../shared/resource-screen/list-search.ts";
import { useListSearch } from "../../shared/resource-screen/use-list-search.ts";
import { PaymentDetailContainer } from "./PaymentDetailContainer.tsx";
import { PaymentsAreaContainer } from "./PaymentsAreaContainer.tsx";
import { PaymentsContainer } from "./PaymentsContainer.tsx";
import { PAYMENTS_PATH, PAYMENTS_PATHS } from "./payments-paths.ts";
import { paymentsSearch, type PaymentsSearch } from "./payments-search.ts";
import { RegisterPaymentContainer } from "./RegisterPaymentContainer.tsx";

export { PAYMENTS_PATH };

function PaymentsRoute() {
  const [search, onSearchChange] = useListSearch<PaymentsSearch>((raw) => paymentsSearch.parse(raw));
  return <PaymentsContainer search={search} onSearchChange={onSearchChange} />;
}

function PaymentRoute() {
  const params: unknown = useParams({ strict: false });
  const { paymentId } = z.object({ paymentId: z.string().optional() }).parse(params);
  return paymentId === undefined ? null : <PaymentDetailContainer paymentId={paymentId} />;
}

/** The part of the address after the area, which is how a child route names itself. */
const below = (path: string): string => path.slice(PAYMENTS_PATH.length + 1);

/**
 * What the payments register with the app: their entries in the side navigation and their routes, children of the signed in shell and so
 * already behind the session guard. The list of payments is the address of the area (an exact entry, so it is not current on the pages below
 * it) and validates its own search (page, size, order and filters), which lives in the address; the register of a payment and the detail of one
 * live below it. The session gives the interface no permissions, so the entries are offered to every signed in person and the server answers a
 * person without the permission with a 403 that each screen tells plainly.
 */
export const paymentsFeature: FeatureModule = {
  navigation: { id: "payments", labelKey: "navigation.payments", to: PAYMENTS_PATHS.list, group: "finance", exact: true },
  moreNavigation: [{ id: "payments-register", labelKey: "navigation.paymentsRegister", to: PAYMENTS_PATHS.register, group: "finance" }],
  createRoutes: (parent: AnyRoute) => {
    const area = createRoute({ getParentRoute: () => parent, path: PAYMENTS_PATH, component: PaymentsAreaContainer });
    // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
    const search = { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] };
    return [
      area.addChildren([
        createRoute({
          getParentRoute: () => area,
          path: "/",
          validateSearch: (raw: Record<string, unknown>) => paymentsSearch.parse(raw),
          search,
          component: PaymentsRoute,
        }),
        createRoute({ getParentRoute: () => area, path: below(PAYMENTS_PATHS.register), component: RegisterPaymentContainer }),
        createRoute({ getParentRoute: () => area, path: "$paymentId", component: PaymentRoute }),
      ]),
    ];
  },
};
