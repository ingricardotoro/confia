import { createRoute, stripSearchParams, type AnyRoute } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../organization/list-search.ts";
import { useListSearch } from "../organization/use-list-search.ts";
import { ImportsContainer } from "./ImportsContainer.tsx";
import { IMPORTS_PATH } from "./imports-paths.ts";
import { importsSearch, type ImportsSearch } from "./imports-search.ts";

export { IMPORTS_PATH };

function ImportsRoute() {
  const { t } = useAdminText();
  const [search, onSearchChange] = useListSearch<ImportsSearch>((raw) => importsSearch.parse(raw));
  return (
    <PageContent title={t("imports.title")}>
      <ImportsContainer search={search} onSearchChange={onSearchChange} />
    </PageContent>
  );
}

/**
 * What the imports screen registers with the app: its entry in the side navigation and its route, a child of the signed in
 * shell and so already behind the session guard. The only search is the page of the history, validated here and kept in the
 * address; nothing about a file ever reaches it.
 */
export const importsFeature: FeatureModule = {
  navigation: { id: "imports", labelKey: "navigation.imports", to: IMPORTS_PATH },
  createRoutes: (parent: AnyRoute) => [
    createRoute({
      getParentRoute: () => parent,
      path: IMPORTS_PATH,
      validateSearch: (raw: Record<string, unknown>) => importsSearch.parse(raw),
      // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
      search: { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] },
      component: ImportsRoute,
    }),
  ],
};
