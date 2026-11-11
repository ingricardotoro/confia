import { createRootRoute, createRoute, createRouter, Outlet, type RouterHistory } from "@tanstack/react-router";
import { ACCESS_PATHS, createIdentityRoutes, leaveSecondFactorScreens, requireAuthentication } from "../features/identity/routes.tsx";
import type { AccessSession } from "../features/identity/session/index.ts";
import { useAdminText } from "../i18n/use-admin-text.ts";
import { PageContent } from "../shared/layout/components/PageContent.tsx";
import type { NavigationEntry } from "../shared/navigation/navigation-entry.ts";
import { FEATURES } from "./features.ts";
import { NotFoundContainer } from "./NotFoundContainer.tsx";
import { AppShellContainer } from "./shell/AppShellContainer.tsx";

export interface AppRouterOptions {
  readonly session: AccessSession;
  readonly history?: RouterHistory;
}

const HOME_ENTRY: NavigationEntry = { id: "home", labelKey: "navigation.home", to: ACCESS_PATHS.home };

function HomeContainer() {
  const { t } = useAdminText();
  return (
    <PageContent title={t("signedIn.title")}>
      <p className="text-text-secondary">{t("signedIn.message")}</p>
    </PageContent>
  );
}

// Code based routes, no generated tree (AW16, design.md D5): the root here, the access routes in
// features/identity/routes.tsx, and every signed in page under one layout route that holds the shell
// and the session guard. The session is given, never imported, so a test builds its own.
export function createAppRouter({ session, history }: AppRouterOptions) {
  const rootRoute = createRootRoute({
    beforeLoad: ({ location }) => {
      leaveSecondFactorScreens(session, location.pathname);
    },
    component: Outlet,
    notFoundComponent: NotFoundContainer,
  });

  const entries = [HOME_ENTRY, ...FEATURES.map((feature) => feature.navigation)];
  const shellRoute = createRoute({
    getParentRoute: () => rootRoute,
    id: "shell",
    beforeLoad: () => {
      requireAuthentication(session);
    },
    component: () => <AppShellContainer session={session} signInPath={ACCESS_PATHS.signIn} entries={entries} />,
  });
  const homeRoute = createRoute({ getParentRoute: () => shellRoute, path: ACCESS_PATHS.home, component: HomeContainer });

  return createRouter({
    routeTree: rootRoute.addChildren([
      shellRoute.addChildren([homeRoute, ...FEATURES.flatMap((feature) => feature.createRoutes(shellRoute))]),
      ...createIdentityRoutes(rootRoute, session),
    ]),
    ...(history ? { history } : {}),
  });
}

export type AppRouter = ReturnType<typeof createAppRouter>;

declare module "@tanstack/react-router" {
  interface Register {
    router: AppRouter;
  }
}
