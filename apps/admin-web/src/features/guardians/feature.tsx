import { createRoute, stripSearchParams, type AnyRoute } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../organization/list-search.ts";
import { useListSearch } from "../organization/use-list-search.ts";
import { GuardiansContainer } from "./GuardiansContainer.tsx";
import { GUARDIANS_PATH } from "./guardians-paths.ts";
import { guardiansSearch, type GuardiansSearch } from "./guardians-search.ts";

export { GUARDIANS_PATH };

function GuardiansRoute() {
  const { t } = useAdminText();
  const [search, onSearchChange] = useListSearch<GuardiansSearch>((raw) => guardiansSearch.parse(raw));
  return (
    <PageContent title={t("guardians.title")}>
      <GuardiansContainer search={search} onSearchChange={onSearchChange} />
    </PageContent>
  );
}

/**
 * What the guardians registry registers with the app: its entry in the side navigation and its route, a child of the
 * signed in shell and so already behind the session guard. The search (page, size, order, text and status) is validated
 * here and lives in the address.
 */
export const guardiansFeature: FeatureModule = {
  navigation: { id: "guardians", labelKey: "navigation.guardians", to: GUARDIANS_PATH },
  createRoutes: (parent: AnyRoute) => [
    createRoute({
      getParentRoute: () => parent,
      path: GUARDIANS_PATH,
      validateSearch: (raw: Record<string, unknown>) => guardiansSearch.parse(raw),
      // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
      search: { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] },
      component: GuardiansRoute,
    }),
  ],
};
