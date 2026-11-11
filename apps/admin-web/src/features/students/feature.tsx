import { createRoute, stripSearchParams, type AnyRoute } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../organization/list-search.ts";
import { useListSearch } from "../organization/use-list-search.ts";
import { StudentsContainer } from "./StudentsContainer.tsx";
import { STUDENTS_PATH } from "./students-paths.ts";
import { studentsSearch, type StudentsSearch } from "./students-search.ts";

export { STUDENTS_PATH };

function StudentsRoute() {
  const { t } = useAdminText();
  const [search, onSearchChange] = useListSearch<StudentsSearch>((raw) => studentsSearch.parse(raw));
  return (
    <PageContent title={t("students.title")}>
      <StudentsContainer search={search} onSearchChange={onSearchChange} />
    </PageContent>
  );
}

/**
 * What the students registry registers with the app: its entry in the side navigation and its route, a child of the
 * signed in shell and so already behind the session guard. The search (page, size, order, text and status) is
 * validated here and lives in the address.
 */
export const studentsFeature: FeatureModule = {
  navigation: { id: "students", labelKey: "navigation.students", to: STUDENTS_PATH },
  createRoutes: (parent: AnyRoute) => [
    createRoute({
      getParentRoute: () => parent,
      path: STUDENTS_PATH,
      validateSearch: (raw: Record<string, unknown>) => studentsSearch.parse(raw),
      // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
      search: { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] },
      component: StudentsRoute,
    }),
  ],
};
