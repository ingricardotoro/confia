import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../../../../shared/api/auth-operations.ts";
import { authenticatedSession, noContent } from "../../../../test/contract-responses.ts";
import { createFakeLocks } from "../../../../test/fake-locks.ts";
import { installMswServer, requests, route } from "../../../../test/msw-server.ts";
import { createAccessSession } from "../access-session.ts";
import { ABSOLUTE_WARNING_LEAD_MS, IDLE_WARNING_LEAD_MS } from "./policy.ts";
import { createLifecycleScheduler, type LifecycleScheduler } from "./scheduler.ts";

// admin-web-shell-and-session-lifecycle, task 2.3 (SS19 precisado, SS133, SS135, SS136, SS141, SS150, SS167,
// SS176). The real session, the real scheduler and the real transport over the test server, with the clock
// and the timers of Vitest: an hour passes in an instant, and what the server saw is what is asserted.
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const REFRESH = `${SESSIONS}/refresh`;
const CURRENT = `${SESSIONS}/current`;
const START = Date.parse("2026-11-10T10:00:00Z");
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
  vi.setSystemTime(START);
});

/** Every scheduler listens to the global document and window, so each one is disposed with its test. */
const created: LifecycleScheduler[] = [];

afterEach(() => {
  for (const scheduler of created.splice(0)) scheduler.dispose();
  vi.useRealTimers();
});

async function signedInWithScheduler(deadlines: { idleInMs: number; sessionInMs: number } = { idleInMs: 30 * MINUTE, sessionInMs: 12 * HOUR }) {
  route(
    "post",
    SESSIONS,
    authenticatedSession({
      override: {
        accessTokenExpiresAt: new Date(START + 10 * MINUTE).toISOString(),
        idleExpiresAt: new Date(START + deadlines.idleInMs).toISOString(),
        sessionExpiresAt: new Date(START + deadlines.sessionInMs).toISOString(),
      },
    }),
  );
  // A refresh is answerable, so one that should not happen is recorded and shows in the counts below.
  route("post", REFRESH, authenticatedSession());
  const session = createAccessSession({ api, locks: createFakeLocks(), now: () => Date.now(), readCsrf: () => "csrf-A" });
  await session.signIn({ identifier: "ana@colegio.edu.hn", password: "not-a-real-password" });
  const scheduler: LifecycleScheduler = createLifecycleScheduler({
    session,
    timers: {
      setTimeout: (callback, ms) => globalThis.setTimeout(callback, ms) as unknown as number,
      clearTimeout: (handle) => {
        globalThis.clearTimeout(handle);
      },
    },
    now: () => Date.now(),
    policy: { idleWarningLeadMs: IDLE_WARNING_LEAD_MS, absoluteWarningLeadMs: ABSOLUTE_WARNING_LEAD_MS },
    document,
    window,
  });
  created.push(scheduler);
  scheduler.replan();
  return { session, scheduler };
}

const refreshes = () => requests().filter((sent) => sent.path === REFRESH);
const deletes = () => requests().filter((sent) => sent.method === "DELETE");

describe("SS19 precisado: with the clock moving and no activity there are no refreshes, only the closing DELETE", () => {
  it("advances 60 minutes: zero refreshes, one DELETE when the countdown ends, and then the sign in page", async () => {
    route("delete", CURRENT, noContent());
    const { session } = await signedInWithScheduler();

    await vi.advanceTimersByTimeAsync(60 * MINUTE);

    expect(refreshes()).toHaveLength(0);
    expect(deletes()).toHaveLength(1);
    expect(deletes()[0]?.headers.get("X-CSRF-Token")).toBe("csrf-A");
    expect(deletes()[0]?.headers.has("Authorization")).toBe(false);
    expect(deletes()[0]?.at).toBe(START + 30 * MINUTE);
    expect(requests().map((sent) => sent.method)).toEqual(["POST", "DELETE"]);
    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
  });

  it("SS141: at no moment of the 25 minutes before the warning, nor of the countdown, is more than one timer pending", async () => {
    route("delete", CURRENT, noContent());
    await signedInWithScheduler();
    const counts: number[] = [];

    for (let minute = 0; minute < 31; minute += 1) {
      await vi.advanceTimersByTimeAsync(MINUTE);
      counts.push(vi.getTimerCount());
    }

    expect(Math.max(...counts)).toBeLessThanOrEqual(1);
    expect(counts.at(-1)).toBe(0);
  });

  it("SS133: after the session ends no timer is left pending and the clock moving does nothing more", async () => {
    route("delete", CURRENT, noContent());
    await signedInWithScheduler();
    await vi.advanceTimersByTimeAsync(31 * MINUTE);
    const sent = requests().length;

    await vi.advanceTimersByTimeAsync(5 * HOUR);

    expect(vi.getTimerCount()).toBe(0);
    expect(requests()).toHaveLength(sent);
  });
});

describe("SS150: no activity, no refreshes", () => {
  it("20 minutes with focus lost and regained, visibility changes, offline and online: zero requests after the sign in", async () => {
    await signedInWithScheduler();

    for (let minute = 0; minute < 20; minute += 1) {
      await vi.advanceTimersByTimeAsync(MINUTE);
      window.dispatchEvent(new Event("blur"));
      window.dispatchEvent(new Event("focus"));
      document.dispatchEvent(new Event("visibilitychange"));
      window.dispatchEvent(new Event("offline"));
      window.dispatchEvent(new Event("online"));
    }

    expect(requests().map((sent) => sent.method)).toEqual(["POST"]);
    expect(refreshes()).toHaveLength(0);
  });
});

describe("the clock jumps with the timers unfired (SS135, SS136)", () => {
  it("SS135: 27 minutes at once, then the tab shows: the warning has 3 minutes left and nothing was asked of the server", async () => {
    const { scheduler } = await signedInWithScheduler();

    vi.setSystemTime(START + 27 * MINUTE);
    document.dispatchEvent(new Event("visibilitychange"));

    expect(scheduler.getSnapshot()).toEqual({ phase: { kind: "idle-warning", endsAt: START + 30 * MINUTE }, remainingMs: 3 * MINUTE });
    expect(requests().map((sent) => sent.method)).toEqual(["POST"]);
  });

  it("SS136: 31 minutes at once, then the tab shows: the session ends with the notice, the warning never showed, one DELETE", async () => {
    route("delete", CURRENT, noContent());
    const { session, scheduler } = await signedInWithScheduler();
    const phases: string[] = [];
    scheduler.subscribe(() => phases.push(scheduler.getSnapshot().phase.kind));

    vi.setSystemTime(START + 31 * MINUTE);
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(0);

    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    expect(phases).not.toContain("idle-warning");
    expect(deletes()).toHaveLength(1);
    expect(refreshes()).toHaveLength(0);
  });

  it("SS176: reaching the absolute deadline ends the session with the absolute reason, with one DELETE and no refresh", async () => {
    route("delete", CURRENT, noContent());
    const { session } = await signedInWithScheduler({ idleInMs: 20 * HOUR, sessionInMs: 10 * MINUTE });

    await vi.advanceTimersByTimeAsync(10 * MINUTE);

    expect(session.getView()).toEqual({ status: "anonymous", notice: "session-expired" });
    expect(deletes()).toHaveLength(1);
    expect(deletes()[0]?.at).toBe(START + 10 * MINUTE);
    expect(refreshes()).toHaveLength(0);
  });
});
