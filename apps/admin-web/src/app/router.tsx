import { createRootRoute, createRouter, Outlet, type RouterHistory } from "@tanstack/react-router";
import { createIdentityRoutes, leaveSecondFactorScreens } from "../features/identity/routes.tsx";
import type { AccessSession } from "../features/identity/session/index.ts";
import { NotFoundContainer } from "./NotFoundContainer.tsx";

export interface AppRouterOptions {
  readonly session: AccessSession;
  readonly history?: RouterHistory;
}

// Code based routes, no generated tree (AW16, design.md D5): the root here, the access routes in
// features/identity/routes.tsx. The session is given, never imported, so a test builds its own.
export function createAppRouter({ session, history }: AppRouterOptions) {
  const rootRoute = createRootRoute({
    beforeLoad: ({ location }) => {
      leaveSecondFactorScreens(session, location.pathname);
    },
    component: Outlet,
    notFoundComponent: NotFoundContainer,
  });

  return createRouter({
    routeTree: rootRoute.addChildren(createIdentityRoutes(rootRoute, session)),
    ...(history ? { history } : {}),
  });
}

export type AppRouter = ReturnType<typeof createAppRouter>;

declare module "@tanstack/react-router" {
  interface Register {
    router: AppRouter;
  }
}
