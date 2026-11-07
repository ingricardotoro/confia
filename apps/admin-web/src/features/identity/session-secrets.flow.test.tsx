import { dehydrate } from "@tanstack/react-query";
import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as api from "../../shared/api/auth-operations.ts";
import { authenticatedSession, currentSession, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderApp } from "../../test/render-app.tsx";
import { createAccessSession } from "./session/access-session.ts";

// admin-web-foundations-and-session, task 9.1 (part 9b; SS06, and the screen clause of SS18, SS25 and SS26).
// The whole App is mounted, because production does not expose the QueryClient: both caches are read from
// the client the test gives it. Tokens and identifiers are made at run time (CLAUDE.md rule 13).
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const REFRESH = `${SESSIONS}/refresh`;
const CURRENT = `${SESSIONS}/current`;
const EXPIRED = "Su sesión se cerró por seguridad. Vuelva a iniciar sesión.";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("what the caches and the document hold", () => {
  // The first render of the whole app in a file builds the module graph cold: the waits are explicit.
  it("SS06: the query and mutation caches hold the metadata of the session and no token", { timeout: 20_000 }, async () => {
    const accessToken = `access-${crypto.randomUUID()}`;
    const identifiers = { accountId: crypto.randomUUID(), institutionId: crypto.randomUUID(), sessionId: crypto.randomUUID() };
    route("post", REFRESH, authenticatedSession({ override: { accessToken } }));
    route("get", CURRENT, currentSession({ override: identifiers }));

    const { queryClient } = renderApp({ path: "/", readCsrf: () => "csrf-value-A" });
    await screen.findByText("Su sesión está activa.", {}, { timeout: 10_000 });

    const held = JSON.stringify({
      queries: dehydrate(queryClient, { shouldDehydrateQuery: () => true }),
      mutations: dehydrate(queryClient, { shouldDehydrateMutation: () => true }),
    });
    expect(held).toContain(identifiers.accountId);
    expect(held).not.toContain(accessToken);
    expect(requests().find((sent) => sent.path === CURRENT)?.headers.get("Authorization")).toBe(`Bearer ${accessToken}`);
  });
});

describe("the screen clause of the session scenarios", () => {
  it("SS18: without Web Locks the tab restores with one request, no error and no polling interval", async () => {
    const interval = vi.spyOn(globalThis, "setInterval");
    route("post", REFRESH, authenticatedSession());
    route("get", CURRENT, currentSession());
    const session = createAccessSession({ api, locks: undefined, now: () => Date.now(), readCsrf: () => "csrf-value-A" });

    renderApp({ path: "/", session, readCsrf: () => "csrf-value-A" });

    expect(await screen.findByText("Su sesión está activa.")).toBeInTheDocument();
    expect(requests().filter((sent) => sent.path === REFRESH)).toHaveLength(1);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    // Testing Library polls with its own short interval while it waits; a polling refresh would be minutes.
    expect(interval.mock.calls.filter(([, delay]) => (delay ?? 0) >= 1_000)).toEqual([]);
  });

  it("SS25: a 401 to the read ends the session with no refresh and no second read, and the cache is empty", async () => {
    route("post", REFRESH, authenticatedSession());
    route("get", CURRENT, problem("token-expired", 401));

    const { queryClient } = renderApp({ path: "/", readCsrf: () => "csrf-value-A" });

    expect(await screen.findByText(EXPIRED)).toBeInTheDocument();
    expect(requests().filter((sent) => sent.path === REFRESH)).toHaveLength(1);
    expect(requests().filter((sent) => sent.path === CURRENT)).toHaveLength(1);
    expect(queryClient.getQueryCache().getAll()).toEqual([]);
  });

  it("SS26: a refresh that is not a 200 ends the session, empties the cache and is not tried a second time", async () => {
    // The first refresh restores a token with 59 seconds left, so the read must refresh once more.
    const shortLived = new Date(Date.now() + 59_000).toISOString();
    route("post", REFRESH, authenticatedSession({ override: { accessTokenExpiresAt: shortLived } }), problem("csrf-invalid", 403));
    route("get", CURRENT, currentSession());

    const { queryClient } = renderApp({ path: "/", readCsrf: () => "csrf-value-A" });

    expect(await screen.findByText(EXPIRED)).toBeInTheDocument();
    expect(requests().filter((sent) => sent.path === REFRESH)).toHaveLength(2);
    expect(requests().filter((sent) => sent.path === CURRENT)).toHaveLength(0);
    expect(queryClient.getQueryCache().getAll()).toEqual([]);
  });
});
