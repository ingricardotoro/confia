import { createRoute, redirect, stripSearchParams, useNavigate, useParams, useSearch, type AnyRoute } from "@tanstack/react-router";
import { z } from "zod";
import type { FeatureModule } from "../../shared/navigation/navigation-entry.ts";
import { DEFAULT_PAGE_SIZE } from "../../shared/resource-screen/list-search.ts";
import { useListSearch } from "../../shared/resource-screen/use-list-search.ts";
import { CHARGES_PATH, CHARGES_PATHS } from "./charges-paths.ts";
import {
  accrualRunSearch,
  accrualRunsSearch,
  accrualSearch,
  chargePlansSearch,
  type AccrualRunSearch,
  type AccrualRunsSearch,
  type AccrualSearch,
  type ChargePlansSearch,
} from "./charges-search.ts";
import { AccrualContainer } from "./AccrualContainer.tsx";
import { AccrualRunDetailContainer } from "./AccrualRunDetailContainer.tsx";
import { AccrualRunsContainer } from "./AccrualRunsContainer.tsx";
import { ChargePlanDetailContainer } from "./ChargePlanDetailContainer.tsx";
import { ChargePlansContainer } from "./ChargePlansContainer.tsx";
import { ChargesContainer } from "./ChargesContainer.tsx";

export { CHARGES_PATH };

function ChargePlansRoute() {
  const [search, onSearchChange] = useListSearch<ChargePlansSearch>((raw) => chargePlansSearch.parse(raw));
  return <ChargePlansContainer search={search} onSearchChange={onSearchChange} />;
}

function ChargePlanDetailRoute() {
  const params: unknown = useParams({ strict: false });
  const { planId } = z.object({ planId: z.string().optional() }).parse(params);
  return planId === undefined ? null : <ChargePlanDetailContainer planId={planId} />;
}

function AccrualRoute() {
  const raw: unknown = useSearch({ strict: false });
  const navigate = useNavigate();
  const search = accrualSearch.parse(raw);
  return (
    <AccrualContainer
      search={search}
      onSearchChange={(next: AccrualSearch) => {
        // The choice is in the address, so it is shareable; a part that is not chosen is not written.
        void navigate({ search: Object.fromEntries(Object.entries(next).filter(([, value]) => value !== undefined)) as never, replace: true });
      }}
    />
  );
}

function AccrualRunsRoute() {
  const [search, onSearchChange] = useListSearch<AccrualRunsSearch>((raw) => accrualRunsSearch.parse(raw));
  return <AccrualRunsContainer search={search} onSearchChange={onSearchChange} />;
}

function AccrualRunRoute() {
  const params: unknown = useParams({ strict: false });
  const { runId } = z.object({ runId: z.string().optional() }).parse(params);
  const [search, onSearchChange] = useListSearch<AccrualRunSearch>((raw) => accrualRunSearch.parse(raw));
  return runId === undefined ? null : <AccrualRunDetailContainer runId={runId} search={search} onSearchChange={onSearchChange} />;
}

/** The part of the address after the area, which is how a child route names itself. */
const below = (path: string): string => path.slice(CHARGES_PATH.length + 1);

/**
 * What the charges register with the app: their entry in the side navigation and their routes, children of the signed in
 * shell and so already behind the session guard. `/charges` leads to the plans (the area has no page of its own); the
 * plans list validates its own search (page, size, order and filters), which lives in the address, and the detail of one plan lives below it.
 * The generation of charges has its own entry beside it: its page (the plan and the period in the address), the list of runs and one run below it.
 */
export const chargesFeature: FeatureModule = {
  navigation: { id: "charges", labelKey: "navigation.charges", to: CHARGES_PATHS.plans, group: "finance" },
  moreNavigation: [{ id: "charges-accrual", labelKey: "navigation.accrual", to: CHARGES_PATHS.accrual, group: "finance" }],
  createRoutes: (parent: AnyRoute) => {
    const area = createRoute({ getParentRoute: () => parent, path: CHARGES_PATH, component: ChargesContainer });
    // A parameter that holds its default is not written in the address (docs/ui-ux/04, section 3.2, rule 1).
    const search = { middlewares: [stripSearchParams({ page: 1, pageSize: DEFAULT_PAGE_SIZE })] };
    return [
      area.addChildren([
        createRoute({
          getParentRoute: () => area,
          path: "/",
          beforeLoad: () => {
            // The area is only a container: its address leads to its first view.
            redirect({ to: CHARGES_PATHS.plans, replace: true, throw: true });
          },
        }),
        createRoute({
          getParentRoute: () => area,
          path: below(CHARGES_PATHS.plans),
          validateSearch: (raw: Record<string, unknown>) => chargePlansSearch.parse(raw),
          search,
          component: ChargePlansRoute,
        }),
        createRoute({ getParentRoute: () => area, path: `${below(CHARGES_PATHS.plans)}/$planId`, component: ChargePlanDetailRoute }),
        createRoute({
          getParentRoute: () => area,
          path: below(CHARGES_PATHS.accrual),
          validateSearch: (raw: Record<string, unknown>) => accrualSearch.parse(raw),
          component: AccrualRoute,
        }),
        createRoute({
          getParentRoute: () => area,
          path: below(CHARGES_PATHS.accrualRuns),
          validateSearch: (raw: Record<string, unknown>) => accrualRunsSearch.parse(raw),
          search,
          component: AccrualRunsRoute,
        }),
        createRoute({
          getParentRoute: () => area,
          path: `${below(CHARGES_PATHS.accrualRuns)}/$runId`,
          validateSearch: (raw: Record<string, unknown>) => accrualRunSearch.parse(raw),
          search,
          component: AccrualRunRoute,
        }),
      ]),
    ];
  },
};
