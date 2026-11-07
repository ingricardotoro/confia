import { render, type RenderResult } from "@testing-library/react";
import { createMemoryHistory } from "@tanstack/react-router";
import { App } from "../app/App.tsx";
import { AppProviders } from "../app/AppProviders.tsx";
import { createQueryClient } from "../app/query-client.ts";
import { createAppRouter, type AppRouter } from "../app/router.tsx";
import { createAdminI18n } from "../i18n/create-admin-i18n.ts";
import { createAccessSession, type AccessApi, type AccessSession } from "../features/identity/session/access-session.ts";
import * as api from "../shared/api/auth-operations.ts";
import { createFakeLocks } from "./fake-locks.ts";

export interface TestSessionOptions {
  /** What the CSRF cookie reads, read again at every use. Absent means no cookie. */
  readonly readCsrf?: () => string | undefined;
  /** The six operations. Defaults to the real ones over the test server; a test may wrap one to hold it. */
  readonly api?: AccessApi;
}

/** A session over the real operations and the real transport, with a fake lock and the real clock. */
export function createTestSession({ readCsrf = () => undefined, api: operations = api }: TestSessionOptions = {}): AccessSession {
  return createAccessSession({ api: operations, locks: createFakeLocks(), now: () => Date.now(), readCsrf });
}

export interface RenderedApp extends RenderResult {
  readonly router: AppRouter;
  readonly session: AccessSession;
  readonly queryClient: ReturnType<typeof createQueryClient>;
}

export interface RenderAppOptions extends TestSessionOptions {
  readonly path?: string;
  /** Defaults to a new session built from the same options. */
  readonly session?: AccessSession;
}

/** The whole app, as `main.tsx` mounts it, over a memory history at `path`. */
export function renderApp({ path = "/", session, ...sessionOptions }: RenderAppOptions = {}): RenderedApp {
  const readCsrf = sessionOptions.readCsrf ?? (() => undefined);
  const held = session ?? createTestSession(sessionOptions);
  const queryClient = createQueryClient();
  const router = createAppRouter({ session: held, history: createMemoryHistory({ initialEntries: [path] }) });
  const rendered = render(
    <AppProviders queryClient={queryClient} i18n={createAdminI18n()}>
      <App router={router} session={held} readCsrf={readCsrf} />
    </AppProviders>,
  );
  return Object.assign(rendered, { router, session: held, queryClient });
}
