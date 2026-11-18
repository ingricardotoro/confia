import { createRoute, redirect, stripSearchParams, type AnyRoute } from "@tanstack/react-router";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../../shared/resource-screen/list-search.ts";
import { useListSearch } from "../../shared/resource-screen/use-list-search.ts";
import { LedgerAccountsContainer } from "./LedgerAccountsContainer.tsx";
import { LedgerContainer } from "./LedgerContainer.tsx";
import { LEDGER_PATH, LEDGER_PATHS } from "./ledger-paths.ts";
import { ledgerAccountsSearch, type LedgerAccountsSearch } from "./ledger-search.ts";
import { PostingMapContainer } from "./PostingMapContainer.tsx";

export { LEDGER_PATH };

function LedgerAccountsRoute() {
  const [search, onSearchChange] = useListSearch<LedgerAccountsSearch>((raw) => ledgerAccountsSearch.parse(raw));
  return <LedgerAccountsContainer search={search} onSearchChange={onSearchChange} />;
}

/** The part of the address after the area, which is how a child route names itself. */
const below = (path: string): string => path.slice(LEDGER_PATH.length + 1);

/**
 * What the chart of accounts registers with the app: its entry in the side navigation (the settings group, beside the
 * academic structure) and its routes, children of the signed in shell and so already behind the session guard. `/ledger`
 * leads to the chart (the area has no page of its own); the chart validates its own search (page, size, order and filters),
 * which lives in the address, and the map of posting accounts is the second view of the area. The map belongs to the charges
 * in the backend, but its screen needs the accounts of the chart, so it lives here and a feature never imports another.
 */
export const ledgerFeature: FeatureModule = {
  navigation: { id: "ledger", labelKey: "navigation.ledger", to: LEDGER_PATH, group: "settings" },
  createRoutes: (parent: AnyRoute) => {
    const area = createRoute({ getParentRoute: () => parent, path: LEDGER_PATH, component: LedgerContainer });
    // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
    const search = { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] };
    return [
      area.addChildren([
        createRoute({
          getParentRoute: () => area,
          path: "/",
          beforeLoad: () => {
            // The area is only a container: its address leads to its first view.
            redirect({ to: LEDGER_PATHS.accounts, replace: true, throw: true });
          },
        }),
        createRoute({
          getParentRoute: () => area,
          path: below(LEDGER_PATHS.accounts),
          validateSearch: (raw: Record<string, unknown>) => ledgerAccountsSearch.parse(raw),
          search,
          component: LedgerAccountsRoute,
        }),
        createRoute({ getParentRoute: () => area, path: below(LEDGER_PATHS.postingMap), component: PostingMapContainer }),
      ]),
    ];
  },
};
