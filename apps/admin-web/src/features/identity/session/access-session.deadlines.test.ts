import type { AuthenticatedSessionResponseOutput } from "@confia/contracts/admin";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../../../shared/api/auth-operations.ts";
import type { ApiResult } from "../../../shared/api/http.ts";
import {
  authenticatedSession,
  currentSession,
  problem,
  secondFactorEnrollmentRequired,
  secondFactorRequired,
  noContent,
} from "../../../test/contract-responses.ts";
import { createFakeLocks } from "../../../test/fake-locks.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createAccessSession, type AccessApi, type AccessSession } from "./access-session.ts";

// admin-web-shell-and-session-lifecycle, task 2.2 (SS128, SS131, SS139, SS140, SS146, SS148, SS149, SS162,
// SS164, SS167 to SS170, SS176; design.md H1, H4, H6). The real operations and the real transport run over
// the test server; the clock is fixed, so every deadline is a known number and no test waits.
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const REFRESH = `${SESSIONS}/refresh`;
const CURRENT = `${SESSIONS}/current`;
const VERIFICATIONS = "/api/v1/auth/mfa/verifications";
const NOW = Date.parse("2026-11-06T10:00:00Z");
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const CREDENTIALS = { identifier: "ana@colegio.edu.hn", password: "not-a-real-password" };

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

const iso = (ms: number): string => new Date(ms).toISOString();

/** An access grant whose deadlines are given as offsets from the test clock. */
function grant(options: { token?: string; endsInMs?: number; idleInMs?: number; sessionInMs?: number } = {}) {
  return authenticatedSession({
    override: {
      accessToken: options.token ?? "token-A",
      accessTokenExpiresAt: iso(NOW + (options.endsInMs ?? 15 * MINUTE)),
      idleExpiresAt: iso(NOW + (options.idleInMs ?? 30 * MINUTE)),
      sessionExpiresAt: iso(NOW + (options.sessionInMs ?? 12 * HOUR)),
    },
  });
}

function build(options: { api?: AccessApi; readCsrf?: () => string | undefined } = {}): AccessSession {
  return createAccessSession({
    api: options.api ?? api,
    locks: createFakeLocks(),
    now: () => NOW,
    readCsrf: options.readCsrf ?? (() => "csrf-A"),
  });
}

async function signedIn(options: Parameters<typeof grant>[0] = {}, session = build()): Promise<AccessSession> {
  route("post", SESSIONS, grant(options));
  await session.signIn(CREDENTIALS);
  return session;
}

const refreshes = () => requests().filter((sent) => sent.path === REFRESH);
const deletes = () => requests().filter((sent) => sent.method === "DELETE");
const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

describe("SS128: every response that carries deadlines leaves them stored", () => {
  it("a sign in with a complete session", async () => {
    const session = await signedIn({ idleInMs: 20 * MINUTE, sessionInMs: 10 * HOUR });

    expect(session.getDeadlines()).toEqual({ idleExpiresAt: NOW + 20 * MINUTE, sessionExpiresAt: NOW + 10 * HOUR, lastRefreshAt: undefined });
  });

  it("a second factor verification", async () => {
    route("post", SESSIONS, secondFactorRequired());
    const session = build();
    await session.signIn(CREDENTIALS);
    expect(session.getDeadlines()).toBeUndefined();
    route("post", VERIFICATIONS, grant({ idleInMs: 21 * MINUTE, sessionInMs: 9 * HOUR }));

    await session.verify({ kind: "code", value: "123456" });

    expect(session.getDeadlines()).toMatchObject({ idleExpiresAt: NOW + 21 * MINUTE, sessionExpiresAt: NOW + 9 * HOUR });
  });

  it("the first code of an enrollment", async () => {
    route("post", SESSIONS, secondFactorEnrollmentRequired());
    const session = build();
    await session.signIn(CREDENTIALS);
    route("post", VERIFICATIONS, grant({ idleInMs: 22 * MINUTE, sessionInMs: 8 * HOUR }));

    await session.activate("123456");

    expect(session.getDeadlines()).toMatchObject({ idleExpiresAt: NOW + 22 * MINUTE, sessionExpiresAt: NOW + 8 * HOUR });
  });

  it("the refresh of a restore, which is also the refresh of this tab", async () => {
    route("post", REFRESH, grant({ idleInMs: 23 * MINUTE, sessionInMs: 7 * HOUR }));
    const session = build();

    await session.boot();

    expect(session.getDeadlines()).toEqual({ idleExpiresAt: NOW + 23 * MINUTE, sessionExpiresAt: NOW + 7 * HOUR, lastRefreshAt: NOW });
  });

  it("the refresh that comes before a request with less than a minute of token", async () => {
    const session = await signedIn({ endsInMs: 59_000 });
    route("post", REFRESH, grant({ token: "token-B", idleInMs: 24 * MINUTE, sessionInMs: 6 * HOUR }));
    route("get", CURRENT, currentSession());

    await session.readCurrentSession();

    expect(session.getDeadlines()?.lastRefreshAt).toBe(NOW);
  });

  it("the read of the current session, which is not a refresh", async () => {
    const session = await signedIn();
    route("get", CURRENT, currentSession({ override: { idleExpiresAt: iso(NOW + 17 * MINUTE), sessionExpiresAt: iso(NOW + 5 * HOUR) } }));

    await session.readCurrentSession();

    expect(session.getDeadlines()).toEqual({ idleExpiresAt: NOW + 17 * MINUTE, sessionExpiresAt: NOW + 5 * HOUR, lastRefreshAt: undefined });
  });
});

describe("the server clock in the session (SS137, SS139, SS140)", () => {
  const SERVER = Date.parse("2026-11-06T10:00:00Z");

  it("converts the deadlines of an answer with the offset its Date header gives", async () => {
    // The browser reads 3 minutes ahead of the server: the header says 10:00:00 when it is 10:03:00.5 here.
    vi.setSystemTime(SERVER + 3 * MINUTE + 500);
    route("post", REFRESH, { ...grant({ idleInMs: 30 * MINUTE }), headers: { Date: new Date(SERVER).toUTCString() } });
    const session = build();

    await session.boot();

    expect(session.getDeadlines()?.idleExpiresAt).toBe(SERVER + 33 * MINUTE);
  });

  it("keeps the offset of the last valid answer when a later one has no Date or an invalid one", async () => {
    vi.setSystemTime(SERVER + 3 * MINUTE + 500);
    route("post", REFRESH, { ...grant(), headers: { Date: new Date(SERVER).toUTCString() } }, { ...grant(), headers: { Date: "abc" } }, grant());
    const session = build();
    await session.boot();

    await session.extend();
    expect(session.getDeadlines()?.idleExpiresAt).toBe(SERVER + 33 * MINUTE);
    await session.extend();
    expect(session.getDeadlines()?.idleExpiresAt).toBe(SERVER + 33 * MINUTE);
  });

  it("uses the deadlines as they come when no answer ever had a Date", async () => {
    route("post", REFRESH, grant({ idleInMs: 30 * MINUTE }));
    const session = build();

    await session.boot();

    expect(session.getDeadlines()?.idleExpiresAt).toBe(NOW + 30 * MINUTE);
  });
});

describe("extend (SS146, SS148, SS149, SS162, SS164, SS131)", () => {
  it("a refresh that works: new token, deadlines replaced even when sooner, and the instant of the refresh", async () => {
    const session = await signedIn({ idleInMs: 30 * MINUTE });
    vi.setSystemTime(NOW + 2 * MINUTE);
    route("post", REFRESH, grant({ token: "token-B", endsInMs: 17 * MINUTE, idleInMs: 20 * MINUTE, sessionInMs: 11 * HOUR }));
    route("get", CURRENT, currentSession());

    const outcome = await session.extend();
    const afterExtend = session.getDeadlines();
    await session.readCurrentSession();

    expect(outcome).toEqual({ kind: "extended" });
    expect(afterExtend).toEqual({ idleExpiresAt: NOW + 20 * MINUTE, sessionExpiresAt: NOW + 11 * HOUR, lastRefreshAt: NOW + 2 * MINUTE });
    expect(requests().at(-1)?.headers.get("Authorization")).toBe("Bearer token-B");
    expect(session.getView()).toEqual({ status: "authenticated" });
  });

  it("a network failure keeps the session, shows nothing and is not repeated", async () => {
    const session = await signedIn();
    const before = session.getDeadlines();
    route("post", REFRESH, "network-error");

    const outcome = await session.extend();
    await flush();

    expect(outcome).toEqual({ kind: "network" });
    expect(session.getView()).toEqual({ status: "authenticated" });
    expect(session.getDeadlines()).toBe(before);
    expect(refreshes()).toHaveLength(1);
  });

  it.each([
    ["session-expired", 401],
    ["csrf-invalid", 403],
  ] as const)("an answer of %s ends the local session with the expired notice, and tries once", async (code, status) => {
    const session = await signedIn();
    route("post", REFRESH, problem(code, status));

    const outcome = await session.extend();

    expect(outcome).toEqual({ kind: "ended" });
    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    expect(session.getDeadlines()).toBeUndefined();
    expect(refreshes()).toHaveLength(1);
  });

  it("with no CSRF cookie nothing is sent and the session ends", async () => {
    const session = await signedIn({}, build({ readCsrf: () => undefined }));

    const outcome = await session.extend();

    expect(outcome).toEqual({ kind: "ended" });
    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    expect(refreshes()).toHaveLength(0);
  });

  it("with no complete session it sends nothing and ends nothing", async () => {
    const session = build();
    route("post", REFRESH, grant());

    expect(await session.extend()).toEqual({ kind: "ended" });

    expect(requests()).toHaveLength(0);
    expect(session.getView()).toEqual({ status: "restoring" });
  });

  it("SS146: two extends and a request that needs a refresh share one request and the same result", async () => {
    const session = await signedIn({ endsInMs: 59_000 });
    route("post", REFRESH, grant({ token: "token-B" }));
    route("get", CURRENT, currentSession());

    const [first, second] = await Promise.all([session.extend(), session.extend(), session.readCurrentSession()]);

    expect(first).toEqual({ kind: "extended" });
    expect(second).toEqual({ kind: "extended" });
    expect(refreshes()).toHaveLength(1);
  });

  it("SS162: three more activations while the first is in flight send no second request", async () => {
    let release: (result: ApiResult<AuthenticatedSessionResponseOutput>) => void = () => undefined;
    const held = new Promise<ApiResult<AuthenticatedSessionResponseOutput>>((resolve) => (release = resolve));
    const refreshSession = vi.fn(() => held);
    const session = await signedIn({}, build({ api: { ...api, refreshSession } }));

    const all = [session.extend(), session.extend(), session.extend(), session.extend()];
    await vi.waitFor(() => {
      expect(refreshSession).toHaveBeenCalledTimes(1);
    });
    release({ kind: "network" });
    const outcomes = await Promise.all(all);

    expect(refreshSession).toHaveBeenCalledTimes(1);
    expect(outcomes).toEqual([{ kind: "network" }, { kind: "network" }, { kind: "network" }, { kind: "network" }]);
  });

  it("an answer that arrives after the session ended does not bring it back", async () => {
    let release: (result: ApiResult<AuthenticatedSessionResponseOutput>) => void = () => undefined;
    const held = new Promise<ApiResult<AuthenticatedSessionResponseOutput>>((resolve) => (release = resolve));
    const refreshSession = vi.fn(() => held);
    route("delete", CURRENT, noContent());
    const session = await signedIn({}, build({ api: { ...api, refreshSession } }));

    const pending = session.extend();
    await vi.waitFor(() => {
      expect(refreshSession).toHaveBeenCalledTimes(1);
    });
    session.endByTimeout("idle");
    release({
      kind: "ok",
      status: 200,
      data: grant().body as AuthenticatedSessionResponseOutput,
      serverDate: undefined,
      receivedAt: NOW,
    });

    expect(await pending).toEqual({ kind: "ended" });
    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    expect(session.getDeadlines()).toBeUndefined();
  });
});

describe("endByTimeout (SS167, SS168, SS169, SS170, SS176)", () => {
  it("SS167: ends the local session first, then sends one DELETE with the CSRF value and no Authorization, and no refresh", async () => {
    route("delete", CURRENT, noContent());
    const session = await signedIn();
    const notified: string[] = [];
    session.subscribe(() => notified.push(session.getView().status));

    session.endByTimeout("idle");

    // Synchronously: before the DELETE has even been asked for.
    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    expect(session.getDeadlines()).toBeUndefined();
    expect(notified).toEqual(["anonymous"]);
    await vi.waitFor(() => {
      expect(deletes()).toHaveLength(1);
    });
    expect(deletes()[0]?.headers.get("X-CSRF-Token")).toBe("csrf-A");
    expect(deletes()[0]?.headers.has("Authorization")).toBe(false);
    expect(refreshes()).toHaveLength(0);
  });

  it("SS176: the absolute reason ends the session the same way", async () => {
    route("delete", CURRENT, noContent());
    const session = await signedIn();

    session.endByTimeout("absolute");

    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    await vi.waitFor(() => {
      expect(deletes()).toHaveLength(1);
    });
  });

  it("does not wait for the DELETE: the local session is over while it is still in flight", async () => {
    let release: (result: ApiResult<undefined>) => void = () => undefined;
    const held = new Promise<ApiResult<undefined>>((resolve) => (release = resolve));
    const endCurrentSession = vi.fn(() => held);
    const session = await signedIn({}, build({ api: { ...api, endCurrentSession } }));

    session.endByTimeout("idle");
    await vi.waitFor(() => {
      expect(endCurrentSession).toHaveBeenCalledExactlyOnceWith("csrf-A");
    });

    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    release({ kind: "ok", status: 204, data: undefined, serverDate: undefined, receivedAt: NOW });
  });

  it.each([
    ["by network", () => Promise.resolve<ApiResult<undefined>>({ kind: "network" })],
    ["with 403 csrf-invalid", () => Promise.resolve<ApiResult<undefined>>({ kind: "problem", problem: { status: 403, code: "csrf-invalid", errors: [] } })],
    ["by throwing", () => Promise.reject(new Error("boom"))],
  ])("SS168: a DELETE that fails %s is not shown and not repeated", async (_name, outcome) => {
    const endCurrentSession = vi.fn(outcome);
    const session = await signedIn({}, build({ api: { ...api, endCurrentSession } }));

    session.endByTimeout("idle");
    await vi.waitFor(() => {
      expect(endCurrentSession).toHaveBeenCalledTimes(1);
    });
    await flush();
    await flush();

    expect(endCurrentSession).toHaveBeenCalledTimes(1);
    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
  });

  it("with no complete session it does nothing: no notice, no DELETE", async () => {
    const endCurrentSession = vi.fn(() => Promise.resolve<ApiResult<undefined>>({ kind: "network" }));
    const session = build({ api: { ...api, endCurrentSession } });

    session.endByTimeout("idle");
    await flush();

    expect(session.getView()).toEqual({ status: "restoring" });
    expect(endCurrentSession).not.toHaveBeenCalled();
  });

  it("SS170: a reload after it, with the cookies gone, restores with no request and shows the sign in page", async () => {
    const reloaded = build({ readCsrf: () => undefined });

    await reloaded.boot();

    expect(requests()).toHaveLength(0);
    expect(reloaded.getView()).toEqual({ status: "anonymous" });
  });
});

describe("the deadlines are listened to", () => {
  it("tells a listener on each change and not for a repeat, and stops after the unsubscribe", async () => {
    const session = build();
    const heard: (number | undefined)[] = [];
    const unsubscribe = session.subscribeDeadlines(() => heard.push(session.getDeadlines()?.idleExpiresAt));

    route("post", SESSIONS, grant({ idleInMs: 30 * MINUTE }));
    await session.signIn(CREDENTIALS);
    session.endByTimeout("idle");
    unsubscribe();
    route("post", SESSIONS, grant({ idleInMs: 10 * MINUTE }));
    await session.signIn(CREDENTIALS);

    expect(heard).toEqual([NOW + 30 * MINUTE, undefined]);
  });
});
