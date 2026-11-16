import { createRoute, stripSearchParams, useParams, type AnyRoute } from "@tanstack/react-router";
import { z } from "zod";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../../shared/resource-screen/list-search.ts";
import { useListSearch } from "../../shared/resource-screen/use-list-search.ts";
import { CATALOG_PATH, CATALOG_PATHS } from "./catalog-paths.ts";
import { paymentConceptsSearch, priceListsSearch, type PaymentConceptsSearch, type PriceListsSearch } from "./catalog-search.ts";
import { CatalogContainer } from "./CatalogContainer.tsx";
import { PaymentConceptsContainer } from "./PaymentConceptsContainer.tsx";
import { PriceListDetailContainer } from "./PriceListDetailContainer.tsx";
import { PriceListsContainer } from "./PriceListsContainer.tsx";

export { CATALOG_PATH };

function PaymentConceptsRoute() {
  const [search, onSearchChange] = useListSearch<PaymentConceptsSearch>((raw) => paymentConceptsSearch.parse(raw));
  return <PaymentConceptsContainer search={search} onSearchChange={onSearchChange} />;
}

function PriceListsRoute() {
  const [search, onSearchChange] = useListSearch<PriceListsSearch>((raw) => priceListsSearch.parse(raw));
  return <PriceListsContainer search={search} onSearchChange={onSearchChange} />;
}

function PriceListDetailRoute() {
  const params: unknown = useParams({ strict: false });
  const { priceListId } = z.object({ priceListId: z.string().optional() }).parse(params);
  return priceListId === undefined ? null : <PriceListDetailContainer priceListId={priceListId} />;
}

/** The part of the address after the area, which is how a child route names itself. */
const below = (path: string): string => path.slice(CATALOG_PATH.length + 1);

/**
 * What the catalog registers with the app: its entry in the side navigation and its routes, children of the signed in
 * shell and so already behind the session guard. `/catalog` is the payment concepts list (the area's own address, so
 * the links that already existed keep working); the price lists and the detail of one live below it. Each list
 * validates its own search (page, size, order and filters), which lives in the address.
 */
export const catalogFeature: FeatureModule = {
  navigation: { id: "catalog", labelKey: "navigation.catalog", to: CATALOG_PATH },
  createRoutes: (parent: AnyRoute) => {
    const area = createRoute({ getParentRoute: () => parent, path: CATALOG_PATH, component: CatalogContainer });
    // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
    const search = { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] };
    return [
      area.addChildren([
        createRoute({
          getParentRoute: () => area,
          path: "/",
          validateSearch: (raw: Record<string, unknown>) => paymentConceptsSearch.parse(raw),
          search,
          component: PaymentConceptsRoute,
        }),
        createRoute({
          getParentRoute: () => area,
          path: below(CATALOG_PATHS.priceLists),
          validateSearch: (raw: Record<string, unknown>) => priceListsSearch.parse(raw),
          search,
          component: PriceListsRoute,
        }),
        createRoute({ getParentRoute: () => area, path: `${below(CATALOG_PATHS.priceLists)}/$priceListId`, component: PriceListDetailRoute }),
      ]),
    ];
  },
};
