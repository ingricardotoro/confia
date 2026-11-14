import type { ReactElement } from "react";
import { createRoute, redirect, stripSearchParams, type AnyRoute } from "@tanstack/react-router";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { AcademicYearsContainer } from "./academic-years/AcademicYearsContainer.tsx";
import { GradesContainer } from "./grades/GradesContainer.tsx";
import {
  DEFAULT_PAGE_SIZE,
  academicYearsSearch,
  gradesSearch,
  modalitiesSearch,
  sectionsSearch,
  type AcademicYearsSearch,
  type GradesSearch,
  type ModalitiesSearch,
  type SectionsSearch,
} from "./list-search.ts";
import { ModalitiesContainer } from "./modalities/ModalitiesContainer.tsx";
import { OrganizationContainer } from "./OrganizationContainer.tsx";
import { ORGANIZATION_PATH, ORGANIZATION_PATHS } from "./organization-paths.ts";
import { SectionsContainer } from "./sections/SectionsContainer.tsx";
import { useListSearch } from "./use-list-search.ts";

export { ORGANIZATION_PATH };

function AcademicYearsRoute() {
  const [search, onSearchChange] = useListSearch<AcademicYearsSearch>((raw) => academicYearsSearch.parse(raw));
  return <AcademicYearsContainer search={search} onSearchChange={onSearchChange} />;
}

function ModalitiesRoute() {
  const [search, onSearchChange] = useListSearch<ModalitiesSearch>((raw) => modalitiesSearch.parse(raw));
  return <ModalitiesContainer search={search} onSearchChange={onSearchChange} />;
}

function GradesRoute() {
  const [search, onSearchChange] = useListSearch<GradesSearch>((raw) => gradesSearch.parse(raw));
  return <GradesContainer search={search} onSearchChange={onSearchChange} />;
}

function SectionsRoute() {
  const [search, onSearchChange] = useListSearch<SectionsSearch>((raw) => sectionsSearch.parse(raw));
  return <SectionsContainer search={search} onSearchChange={onSearchChange} />;
}

/**
 * What the academic structure registers with the app: its entry in the side navigation and its routes. The
 * routes are children of the signed in shell, so they are already behind the session guard. `/organization` alone
 * leads to the first view; each view validates its own search parameters.
 */
export const organizationFeature: FeatureModule = {
  navigation: { id: "organization", labelKey: "navigation.organization", to: ORGANIZATION_PATH },
  createRoutes: (parent: AnyRoute) => {
    const area = createRoute({ getParentRoute: () => parent, path: ORGANIZATION_PATH, component: OrganizationContainer });
    const index = createRoute({
      getParentRoute: () => area,
      path: "/",
      beforeLoad: () => {
        redirect({ to: ORGANIZATION_PATHS.academicYears, replace: true, throw: true });
      },
    });
    // The path of a child is relative to the area, and its search is validated by the schema of its view.
    const view = (path: string, validateSearch: (raw: Record<string, unknown>) => unknown, component: () => ReactElement) =>
      createRoute({
        getParentRoute: () => area,
        path: path.slice(ORGANIZATION_PATH.length + 1),
        validateSearch,
        // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
        search: { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] },
        component,
      });

    return [
      area.addChildren([
        index,
        view(ORGANIZATION_PATHS.academicYears, (raw) => academicYearsSearch.parse(raw), AcademicYearsRoute),
        view(ORGANIZATION_PATHS.modalities, (raw) => modalitiesSearch.parse(raw), ModalitiesRoute),
        view(ORGANIZATION_PATHS.grades, (raw) => gradesSearch.parse(raw), GradesRoute),
        view(ORGANIZATION_PATHS.sections, (raw) => sectionsSearch.parse(raw), SectionsRoute),
      ]),
    ];
  },
};
