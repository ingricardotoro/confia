import { createRoute, stripSearchParams, type AnyRoute } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../../shared/resource-screen/list-search.ts";
import { useListSearch } from "../../shared/resource-screen/use-list-search.ts";
import { CATALOG_PATH } from "./catalog-paths.ts";
import { paymentConceptsSearch, type PaymentConceptsSearch } from "./catalog-search.ts";
import { PaymentConceptsContainer } from "./PaymentConceptsContainer.tsx";

export { CATALOG_PATH };

function PaymentConceptsRoute() {
  const { t } = useAdminText();
  const [search, onSearchChange] = useListSearch<PaymentConceptsSearch>((raw) => paymentConceptsSearch.parse(raw));
  return (
    <PageContent title={t("catalog.title")}>
      <PaymentConceptsContainer search={search} onSearchChange={onSearchChange} />
    </PageContent>
  );
}

/**
 * What the catalog registers with the app: its entry in the side navigation and its route, a child of the signed in
 * shell and so already behind the session guard. `/catalog` is the list of payment concepts; its search (page, size,
 * order, category and state) is validated here and lives in the address.
 */
export const catalogFeature: FeatureModule = {
  navigation: { id: "catalog", labelKey: "navigation.catalog", to: CATALOG_PATH },
  createRoutes: (parent: AnyRoute) => [
    createRoute({
      getParentRoute: () => parent,
      path: CATALOG_PATH,
      validateSearch: (raw: Record<string, unknown>) => paymentConceptsSearch.parse(raw),
      // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
      search: { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] },
      component: PaymentConceptsRoute,
    }),
  ],
};
