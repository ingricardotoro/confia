import { createRoute, stripSearchParams, useParams, type AnyRoute } from "@tanstack/react-router";
import { z } from "zod";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../../shared/resource-screen/list-search.ts";
import { useListSearch } from "../../shared/resource-screen/use-list-search.ts";
import { CASHBOX_PATH, CASHBOX_PATHS } from "./cashbox-paths.ts";
import { cashSessionsSearch, type CashSessionsSearch } from "./cashbox-search.ts";
import { CashboxContainer } from "./CashboxContainer.tsx";
import { CashSessionDetailContainer } from "./CashSessionDetailContainer.tsx";
import { CashSessionsContainer } from "./CashSessionsContainer.tsx";
import { MyCashboxContainer } from "./MyCashboxContainer.tsx";

export { CASHBOX_PATH };

function CashSessionsRoute() {
  const [search, onSearchChange] = useListSearch<CashSessionsSearch>((raw) => cashSessionsSearch.parse(raw));
  return <CashSessionsContainer search={search} onSearchChange={onSearchChange} />;
}

function CashSessionRoute() {
  const params: unknown = useParams({ strict: false });
  const { sessionId } = z.object({ sessionId: z.string().optional() }).parse(params);
  return sessionId === undefined ? null : <CashSessionDetailContainer sessionId={sessionId} />;
}

/** The part of the address after the area, which is how a child route names itself. */
const below = (path: string): string => path.slice(CASHBOX_PATH.length + 1);

/**
 * What the cashbox registers with the app: its entries in the side navigation and its routes, children of the signed in shell and so
 * already behind the session guard. The cashier's own cashbox is the area's address (an exact entry, so it is not current on the pages below
 * it); the sessions list validates its own search (page, size, order and filters), which lives in the address, and the report of one session
 * lives below the list. Each entry and route declares the permission of the operations it calls: the own cashbox opens, moves and closes
 * (`cashbox:create`), the list and the report read (`cashbox:read`, the longer prefix wins), and the report offers the approval only with
 * `cashbox:approve`. The interface only hides; the server decides every request again.
 */
export const cashboxFeature: FeatureModule = {
  navigation: { id: "cashbox", labelKey: "navigation.cashbox", to: CASHBOX_PATHS.mine, group: "finance", exact: true, permission: "cashbox:create" },
  moreNavigation: [{ id: "cashbox-sessions", labelKey: "navigation.cashSessions", to: CASHBOX_PATHS.sessions, group: "finance", permission: "cashbox:read" }],
  routePermissions: [
    { path: CASHBOX_PATH, permission: "cashbox:create" },
    { path: CASHBOX_PATHS.sessions, permission: "cashbox:read" },
  ],
  createRoutes: (parent: AnyRoute) => {
    const area = createRoute({ getParentRoute: () => parent, path: CASHBOX_PATH, component: CashboxContainer });
    // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
    const search = { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] };
    return [
      area.addChildren([
        createRoute({ getParentRoute: () => area, path: "/", component: MyCashboxContainer }),
        createRoute({
          getParentRoute: () => area,
          path: below(CASHBOX_PATHS.sessions),
          validateSearch: (raw: Record<string, unknown>) => cashSessionsSearch.parse(raw),
          search,
          component: CashSessionsRoute,
        }),
        createRoute({ getParentRoute: () => area, path: `${below(CASHBOX_PATHS.sessions)}/$sessionId`, component: CashSessionRoute }),
      ]),
    ];
  },
};
