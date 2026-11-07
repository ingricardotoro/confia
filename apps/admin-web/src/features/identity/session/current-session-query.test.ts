import { afterEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../../app/query-client.ts";
import * as api from "../../../shared/api/auth-operations.ts";
import { authenticatedSession, currentSession } from "../../../test/contract-responses.ts";
import { createFakeLocks } from "../../../test/fake-locks.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createAccessSession } from "./access-session.ts";
import { currentSessionQueryOptions, sessionKeys } from "./current-session-query.ts";

// admin-web-foundations-and-session, task 6.1 (part 6b; SS21 and, through the app's query client, SS24).
installMswServer();

const NOW = Date.parse("2026-11-06T10:00:00Z");

afterEach(() => {
  vi.useRealTimers();
});

describe("the current session query", () => {
  it("SS21: has no interval and no refetch on focus or on reconnect, and does not retry", () => {
    const options = currentSessionQueryOptions({ readCurrentSession: () => Promise.reject(new Error("not called")) });

    expect(options.refetchInterval).toBe(false);
    expect(options.refetchOnWindowFocus).toBe(false);
    expect(options.refetchOnReconnect).toBe(false);
    expect(options.retry).toBe(false);
  });

  it("uses a key that sits under the session prefix", () => {
    expect(sessionKeys.current().slice(0, sessionKeys.all.length)).toEqual([...sessionKeys.all]);
  });

  it("SS24: through the app's query client a refresh that fails by network is tried once, even after 60 seconds", async () => {
    vi.useFakeTimers();
    route(
      "post",
      "/api/v1/auth/sessions",
      authenticatedSession({ override: { accessToken: "token-old", accessTokenExpiresAt: new Date(NOW + 59_000).toISOString() } }),
    );
    route("post", "/api/v1/auth/sessions/refresh", "network-error");
    route("get", "/api/v1/auth/sessions/current", currentSession());
    const session = createAccessSession({ api, locks: createFakeLocks(), now: () => NOW, readCsrf: () => "csrf-A" });
    await session.signIn({ identifier: "ana@colegio.edu.hn", password: "not-a-real-password" });

    const settled = createQueryClient()
      .query(currentSessionQueryOptions(session))
      .then(
        () => "resolved",
        () => "rejected",
      );
    await vi.advanceTimersByTimeAsync(60_000);

    await expect(settled).resolves.toBe("rejected");
    expect(requests().filter((sent) => sent.path.endsWith("/refresh"))).toHaveLength(1);
    expect(requests().some((sent) => sent.method === "GET")).toBe(false);
  });
});
