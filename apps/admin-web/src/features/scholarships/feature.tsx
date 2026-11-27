import { createRoute, redirect, stripSearchParams, useNavigate, useParams, useSearch, type AnyRoute } from "@tanstack/react-router";
import { z } from "zod";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../../shared/resource-screen/list-search.ts";
import { useListSearch } from "../../shared/resource-screen/use-list-search.ts";
import { AwardDetailContainer } from "./AwardDetailContainer.tsx";
import { AssignmentDetailContainer } from "./AssignmentDetailContainer.tsx";
import { AssignmentsContainer } from "./AssignmentsContainer.tsx";
import { AwardsContainer } from "./AwardsContainer.tsx";
import { DiscountsContainer } from "./DiscountsContainer.tsx";
import { ScholarshipsAreaContainer } from "./ScholarshipsAreaContainer.tsx";
import { ScholarshipsContainer } from "./ScholarshipsContainer.tsx";
import { assignmentsSearch, discountsSearch, type AssignmentsSearch, type DiscountsSearch } from "./discounts-search.ts";
import { DISCOUNT_PATHS, SCHOLARSHIPS_PATH, SCHOLARSHIPS_PATHS } from "./scholarships-paths.ts";
import {
  awardsSearch,
  scholarshipsSearch,
  simulatorSearch,
  type AwardsSearch,
  type ScholarshipsSearch,
  type SimulatorSearch,
} from "./scholarships-search.ts";
import { SimulatorContainer } from "./SimulatorContainer.tsx";

export { SCHOLARSHIPS_PATH };

function ScholarshipsRoute() {
  const [search, onSearchChange] = useListSearch<ScholarshipsSearch>((raw) => scholarshipsSearch.parse(raw));
  return <ScholarshipsContainer search={search} onSearchChange={onSearchChange} />;
}

function DiscountsRoute() {
  const [search, onSearchChange] = useListSearch<DiscountsSearch>((raw) => discountsSearch.parse(raw));
  return <DiscountsContainer search={search} onSearchChange={onSearchChange} />;
}

function AssignmentsRoute() {
  const [search, onSearchChange] = useListSearch<AssignmentsSearch>((raw) => assignmentsSearch.parse(raw));
  return <AssignmentsContainer search={search} onSearchChange={onSearchChange} />;
}

function AssignmentRoute() {
  const params: unknown = useParams({ strict: false });
  const { assignmentId } = z.object({ assignmentId: z.string().optional() }).parse(params);
  return assignmentId === undefined ? null : <AssignmentDetailContainer assignmentId={assignmentId} />;
}

function AwardsRoute() {
  const [search, onSearchChange] = useListSearch<AwardsSearch>((raw) => awardsSearch.parse(raw));
  return <AwardsContainer search={search} onSearchChange={onSearchChange} />;
}

function AwardRoute() {
  const params: unknown = useParams({ strict: false });
  const { awardId } = z.object({ awardId: z.string().optional() }).parse(params);
  return awardId === undefined ? null : <AwardDetailContainer awardId={awardId} />;
}

function SimulatorRoute() {
  const raw: unknown = useSearch({ strict: false });
  const navigate = useNavigate();
  const search = simulatorSearch.parse(raw);
  return (
    <SimulatorContainer
      search={search}
      onSearchChange={(next: SimulatorSearch) => {
        // The choice is in the address, so it is shareable; a part that is not chosen is not written.
        void navigate({ search: Object.fromEntries(Object.entries(next).filter(([, value]) => value !== undefined)) as never, replace: true });
      }}
    />
  );
}

/** The part of the address after the area, which is how a child route names itself. */
const below = (path: string): string => path.slice(SCHOLARSHIPS_PATH.length + 1);

/**
 * What the scholarships register with the app (and, below them, the discounts and their agreement assignments): their entries in the side navigation and their routes, children of the signed in shell and so already
 * behind the session guard. `/scholarships` leads to the scholarships (the area has no page of its own); each list validates its own search (page,
 * size, order and filters), which lives in the address, and one award lives below the awards. The simulator of charges lives in the same area but only
 * reads the charges, so its entry and its route ask for `charges:read` (the longer prefix wins). The interface only hides; the server decides every
 * request again and answers a person without the permission with a 403 that each screen tells plainly.
 */
export const scholarshipsFeature: FeatureModule = {
  navigation: { id: "scholarships", labelKey: "navigation.scholarships", to: SCHOLARSHIPS_PATHS.list, group: "finance", permission: "scholarships:read" },
  moreNavigation: [
    { id: "scholarships-awards", labelKey: "navigation.awards", to: SCHOLARSHIPS_PATHS.awards, group: "finance", permission: "scholarships:read" },
    { id: "scholarships-discounts", labelKey: "navigation.discounts", to: DISCOUNT_PATHS.discounts, group: "finance", permission: "scholarships:read" },
    { id: "scholarships-discount-assignments", labelKey: "navigation.discountAssignments", to: DISCOUNT_PATHS.assignments, group: "finance", permission: "scholarships:read" },
    { id: "scholarships-simulator", labelKey: "navigation.simulator", to: SCHOLARSHIPS_PATHS.simulator, group: "finance", permission: "charges:read" },
  ],
  routePermissions: [
    { path: SCHOLARSHIPS_PATH, permission: "scholarships:read" },
    { path: SCHOLARSHIPS_PATHS.simulator, permission: "charges:read" },
  ],
  createRoutes: (parent: AnyRoute) => {
    const area = createRoute({ getParentRoute: () => parent, path: SCHOLARSHIPS_PATH, component: ScholarshipsAreaContainer });
    // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
    const search = { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] };
    return [
      area.addChildren([
        createRoute({
          getParentRoute: () => area,
          path: "/",
          beforeLoad: () => {
            // The area is only a container: its address leads to its first view.
            redirect({ to: SCHOLARSHIPS_PATHS.list, replace: true, throw: true });
          },
        }),
        createRoute({
          getParentRoute: () => area,
          path: below(SCHOLARSHIPS_PATHS.list),
          validateSearch: (raw: Record<string, unknown>) => scholarshipsSearch.parse(raw),
          search,
          component: ScholarshipsRoute,
        }),
        createRoute({
          getParentRoute: () => area,
          path: below(SCHOLARSHIPS_PATHS.awards),
          validateSearch: (raw: Record<string, unknown>) => awardsSearch.parse(raw),
          search,
          component: AwardsRoute,
        }),
        createRoute({ getParentRoute: () => area, path: `${below(SCHOLARSHIPS_PATHS.awards)}/$awardId`, component: AwardRoute }),
        createRoute({
          getParentRoute: () => area,
          path: below(DISCOUNT_PATHS.discounts),
          validateSearch: (raw: Record<string, unknown>) => discountsSearch.parse(raw),
          search,
          component: DiscountsRoute,
        }),
        createRoute({
          getParentRoute: () => area,
          path: below(DISCOUNT_PATHS.assignments),
          validateSearch: (raw: Record<string, unknown>) => assignmentsSearch.parse(raw),
          search,
          component: AssignmentsRoute,
        }),
        createRoute({ getParentRoute: () => area, path: `${below(DISCOUNT_PATHS.assignments)}/$assignmentId`, component: AssignmentRoute }),
        createRoute({
          getParentRoute: () => area,
          path: below(SCHOLARSHIPS_PATHS.simulator),
          validateSearch: (raw: Record<string, unknown>) => simulatorSearch.parse(raw),
          component: SimulatorRoute,
        }),
      ]),
    ];
  },
};
