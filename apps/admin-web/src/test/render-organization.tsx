import { QueryClient } from "@tanstack/react-query";
import { render, type RenderResult } from "@testing-library/react";
import { createMemoryHistory, createRootRoute, createRouter, Outlet, RouterProvider } from "@tanstack/react-router";
import { AppProviders } from "../app/AppProviders.tsx";
import { organizationFeature } from "../features/organization/feature.tsx";
import { createAdminI18n } from "../i18n/create-admin-i18n.ts";
import { ApiFailure } from "../shared/api/api-failure.ts";
import { AuthorizedSendProvider, type AuthorizedSend } from "../shared/api/authorized-send.tsx";

/** The token the test sender gives every request. The real one comes from the session; these tests are about the screens. */
export const TEST_TOKEN = "test-access-token";

const send: AuthorizedSend = async (operation) => {
  const result = await operation(TEST_TOKEN);
  if (result.kind === "ok") return result.data;
  throw new ApiFailure(result);
};

export interface RenderedOrganization extends RenderResult {
  /** What the tests read of the router: where it is, and the search it wrote in the address. */
  readonly router: { readonly state: { readonly location: { readonly pathname: string; readonly searchStr: string } } };
  readonly queryClient: QueryClient;
}

/**
 * The academic structure as the app mounts it, under a bare root route: its real routes and search validation, the
 * real query layer and the real transport over the test server. Only the session is replaced, by a sender that
 * gives every request a fixed token; the shell and the session guard have their own tests. Reads do not retry.
 */
export function renderOrganization(path: string): RenderedOrganization {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false }, mutations: { retry: 0 } } });
  const rootRoute = createRootRoute({ component: Outlet });
  const router = createRouter({
    routeTree: rootRoute.addChildren(organizationFeature.createRoutes(rootRoute)),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  const rendered = render(
    <AppProviders queryClient={queryClient} i18n={createAdminI18n()}>
      <AuthorizedSendProvider send={send}>
        <RouterProvider router={router} />
      </AuthorizedSendProvider>
    </AppProviders>,
  );
  return Object.assign(rendered, { router, queryClient });
}
