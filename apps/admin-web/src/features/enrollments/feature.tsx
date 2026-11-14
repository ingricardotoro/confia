import { createRoute, stripSearchParams, type AnyRoute } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../organization/list-search.ts";
import { useListSearch } from "../organization/use-list-search.ts";
import { EnrollmentsContainer } from "./EnrollmentsContainer.tsx";
import { ENROLLMENTS_PATH } from "./enrollments-paths.ts";
import { enrollmentsSearch, type EnrollmentsSearch } from "./enrollments-search.ts";

export { ENROLLMENTS_PATH };

function EnrollmentsRoute() {
  const { t } = useAdminText();
  const [search, onSearchChange] = useListSearch<EnrollmentsSearch>((raw) => enrollmentsSearch.parse(raw));
  return (
    <PageContent title={t("enrollments.title")}>
      <EnrollmentsContainer search={search} onSearchChange={onSearchChange} />
    </PageContent>
  );
}

/**
 * What the enrollments register with the app: their entry in the side navigation and their route, a child of the signed in
 * shell and so already behind the session guard. The search (page, size, order and the filters of year, grade, section and
 * status) is validated here and lives in the address.
 */
export const enrollmentsFeature: FeatureModule = {
  navigation: { id: "enrollments", labelKey: "navigation.enrollments", to: ENROLLMENTS_PATH },
  createRoutes: (parent: AnyRoute) => [
    createRoute({
      getParentRoute: () => parent,
      path: ENROLLMENTS_PATH,
      validateSearch: (raw: Record<string, unknown>) => enrollmentsSearch.parse(raw),
      // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
      search: { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] },
      component: EnrollmentsRoute,
    }),
  ],
};
