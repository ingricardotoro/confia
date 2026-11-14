import { afterEach, describe, expect, it, vi } from "vitest";
import { refreshSession } from "../../../shared/api/auth-operations.ts";
import { authenticatedSession, problem } from "../../../test/contract-responses.ts";
import { createFakeLocks, type FakeLocks } from "../../../test/fake-locks.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createRefreshCoordinator, REFRESH_LOCK_NAME } from "./refresh-coordinator.ts";

// admin-web-foundations-and-session, task 6.1 (part 6a; SS14 to SS16, SS18, and design.md D8, invariant
// 3). The real transport runs over the test server and the lock is a fake with the browser's queueing,
// so what is asserted is what the server saw.
installMswServer();

const REFRESH = "/api/v1/auth/sessions/refresh";

afterEach(() => {
  vi.restoreAllMocks();
});

interface Options {
  readonly readCsrf?: () => string | undefined;
  /** `null` is a browser without Web Locks. */
  readonly locks?: FakeLocks | null;
}

function coordinator(options: Options = {}) {
  return createRefreshCoordinator({
    refresh: refreshSession,
    readCsrf: options.readCsrf ?? (() => "csrf-A"),
    locks: options.locks === null ? undefined : (options.locks ?? createFakeLocks()),
  });
}

/** Another tab holding the lock, until the returned function releases it. Resolves once it holds it. */
async function holdLockAsAnotherTab(locks: FakeLocks): Promise<{ readonly done: Promise<void>; readonly release: () => void }> {
  let release: () => void = () => undefined;
  let held: () => void = () => undefined;
  const acquired = new Promise<void>((resolve) => (held = resolve));
  const done = locks.request(
    REFRESH_LOCK_NAME,
    { mode: "exclusive" },
    () =>
      new Promise<void>((resolve) => {
        release = resolve;
        held();
      }),
  );
  await acquired;
  return {
    done,
    release: () => {
      release();
    },
  };
}

describe("the refresh coordinator", () => {
  it("SS14: three triggers in one instant share a single request and the same outcome", async () => {
    route("post", REFRESH, authenticatedSession());
    const refresh = coordinator();

    const [first, second, third] = await Promise.all([refresh.refreshOnce(), refresh.refreshOnce(), refresh.refreshOnce()]);

    expect(requests()).toHaveLength(1);
    expect(first.kind).toBe("refreshed");
    expect(second).toBe(first);
    expect(third).toBe(first);
  });

  it("SS15: a new trigger after the last one finished makes a new request", async () => {
    route("post", REFRESH, authenticatedSession());
    const refresh = coordinator();

    await refresh.refreshOnce();
    await refresh.refreshOnce();

    expect(requests()).toHaveLength(2);
  });

  it("SS16: a rejection reaches everyone who waited and the shared promise is free afterwards", async () => {
    route("post", REFRESH, problem("session-expired", 401));
    const refresh = coordinator();

    const [first, second] = await Promise.all([refresh.refreshOnce(), refresh.refreshOnce()]);

    expect(first).toEqual({ kind: "rejected" });
    expect(second).toBe(first);
    expect(requests()).toHaveLength(1);

    await refresh.refreshOnce();
    expect(requests()).toHaveLength(2);
  });

  it("answers a network failure as its own outcome, with one attempt", async () => {
    route("post", REFRESH, "network-error");

    await expect(coordinator().refreshOnce()).resolves.toEqual({ kind: "network" });

    expect(requests()).toHaveLength(1);
  });

  it("treats a 200 that breaks the contract as a rejection", async () => {
    route("post", REFRESH, { status: 200, body: { status: "authenticated" }, contentType: "application/json" });

    await expect(coordinator().refreshOnce()).resolves.toEqual({ kind: "rejected" });
  });

  it("sends the CSRF value it read as the header and no token", async () => {
    route("post", REFRESH, authenticatedSession());

    await coordinator({ readCsrf: () => "csrf-B" }).refreshOnce();

    const [sent] = requests();
    expect(sent?.headers.get("X-CSRF-Token")).toBe("csrf-B");
    expect(sent?.headers.has("Authorization")).toBe(false);
  });

  it("takes the exclusive lock named confia.admin.session.refresh", async () => {
    route("post", REFRESH, authenticatedSession());
    const locks = createFakeLocks();

    await coordinator({ locks }).refreshOnce();

    expect(locks.requested).toEqual([{ name: "confia.admin.session.refresh", mode: "exclusive" }]);
  });

  it("invariant 3: reads the CSRF value inside the lock, after another tab rotated it", async () => {
    route("post", REFRESH, authenticatedSession());
    const locks = createFakeLocks();
    let cookie: string | undefined = "csrf-before";
    const otherTab = await holdLockAsAnotherTab(locks);

    const pending = coordinator({ locks, readCsrf: () => cookie }).refreshOnce();
    cookie = "csrf-rotated-by-the-other-tab";
    otherTab.release();
    await otherTab.done;
    await pending;

    expect(requests().map((sent) => sent.headers.get("X-CSRF-Token"))).toEqual(["csrf-rotated-by-the-other-tab"]);
  });

  it("invariant 3: a cookie that is gone when the lock is granted makes no request", async () => {
    route("post", REFRESH, authenticatedSession());
    const locks = createFakeLocks();
    let cookie: string | undefined = "csrf-before";
    const otherTab = await holdLockAsAnotherTab(locks);

    const pending = coordinator({ locks, readCsrf: () => cookie }).refreshOnce();
    cookie = undefined;
    otherTab.release();
    await otherTab.done;

    await expect(pending).resolves.toEqual({ kind: "no-session" });
    expect(requests()).toHaveLength(0);
  });

  it("invariant 3: no cookie on entry means no request", async () => {
    route("post", REFRESH, authenticatedSession());

    await expect(coordinator({ readCsrf: () => undefined }).refreshOnce()).resolves.toEqual({ kind: "no-session" });

    expect(requests()).toHaveLength(0);
  });

  it("admin-web-shell-and-session-lifecycle H3: a refreshed outcome carries the server date and the arrival of the answer", async () => {
    route("post", REFRESH, { ...authenticatedSession(), headers: { Date: "Tue, 10 Nov 2026 10:00:00 GMT" } });
    const before = Date.now();

    const outcome = await coordinator().refreshOnce();

    expect(outcome.kind).toBe("refreshed");
    if (outcome.kind !== "refreshed") return;
    expect(outcome.serverDate).toBe(Date.UTC(2026, 10, 10, 10, 0, 0));
    expect(outcome.receivedAt).toBeGreaterThanOrEqual(before);
  });

  it("H3: without a Date header the server date of the outcome is absent", async () => {
    route("post", REFRESH, authenticatedSession());

    const outcome = await coordinator().refreshOnce();

    expect(outcome.kind === "refreshed" && outcome.serverDate).toBeUndefined();
    expect(outcome.kind === "refreshed" && typeof outcome.receivedAt).toBe("number");
  });

  // Dated note 2026-11-10 (admin-web-shell-and-session-lifecycle, design.md section 2, SS19 precisado): the
  // invariant below used to read "no timer". It is now "no timer or background query asks for a refresh": the
  // lifecycle scheduler holds at most one timer, and it never calls the coordinator. Only a caller starts one.
  it("SS18: without Web Locks it still makes one shared request, with no error and no polling timer", async () => {
    route("post", REFRESH, authenticatedSession());
    const interval = vi.spyOn(globalThis, "setInterval");
    const refresh = coordinator({ locks: null });

    const [first, second] = await Promise.all([refresh.refreshOnce(), refresh.refreshOnce()]);

    expect(first.kind).toBe("refreshed");
    expect(second).toBe(first);
    expect(requests()).toHaveLength(1);
    expect(interval).not.toHaveBeenCalled();
  });
});
