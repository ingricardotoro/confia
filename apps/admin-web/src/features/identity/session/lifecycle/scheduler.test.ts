import { describe, expect, it, vi } from "vitest";
import type { LocalDeadlines } from "../deadline-store.ts";
import type { SessionView } from "../access-session.ts";
import { createLifecycleScheduler, type SchedulerSession, type Timers } from "./scheduler.ts";
import type { LifecyclePhase, LifecyclePolicy } from "./planner.ts";

// admin-web-shell-and-session-lifecycle, task 2.1 (SS135, SS136, SS141, SS150, design.md H2 and section 7).
// The scheduler runs over a hand-made clock and hand-made timers: nothing waits, and the test can jump the
// clock the way a suspended machine does, with the timers left unfired.
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const T = Date.parse("2026-11-10T10:00:00Z");
const POLICY: LifecyclePolicy = { idleWarningLeadMs: 5 * MINUTE, absoluteWarningLeadMs: 5 * MINUTE };

function setup(initial: { idle?: number; session?: number } = {}) {
  const clock = { now: T };
  const pending = new Map<number, { at: number; run: () => void }>();
  let handles = 0;
  let maxPending = 0;
  const timers: Timers = {
    setTimeout(callback, ms) {
      handles += 1;
      pending.set(handles, { at: clock.now + ms, run: callback });
      maxPending = Math.max(maxPending, pending.size);
      return handles;
    },
    clearTimeout(handle) {
      pending.delete(handle);
    },
  };
  /** The clock moves and every timer due on the way fires, in order. */
  function advanceTo(target: number): void {
    for (;;) {
      const due = [...pending.entries()].filter(([, timer]) => timer.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
      if (due === undefined) break;
      pending.delete(due[0]);
      clock.now = due[1].at;
      due[1].run();
    }
    clock.now = target;
  }

  let view: SessionView = { status: "authenticated" };
  let deadlines: LocalDeadlines | undefined = {
    idleExpiresAt: initial.idle ?? T + 30 * MINUTE,
    sessionExpiresAt: initial.session ?? T + 12 * HOUR,
    lastRefreshAt: T,
  };
  const viewListeners = new Set<() => void>();
  const deadlineListeners = new Set<() => void>();
  const endByTimeout = vi.fn<(reason: "idle" | "absolute") => void>();
  const session: SchedulerSession = {
    getView: () => view,
    subscribe(listener) {
      viewListeners.add(listener);
      return () => viewListeners.delete(listener);
    },
    getDeadlines: () => deadlines,
    subscribeDeadlines(listener) {
      deadlineListeners.add(listener);
      return () => deadlineListeners.delete(listener);
    },
    endByTimeout,
  };
  const documentTarget: EventTarget & { visibilityState: DocumentVisibilityState } = Object.assign(new EventTarget(), {
    visibilityState: "visible" as const,
  });
  const windowTarget = new EventTarget();
  const scheduler = createLifecycleScheduler({
    session,
    timers,
    now: () => clock.now,
    policy: POLICY,
    document: documentTarget,
    window: windowTarget,
  });
  const phases: LifecyclePhase["kind"][] = [];
  scheduler.subscribe(() => phases.push(scheduler.getSnapshot().phase.kind));

  return {
    scheduler,
    clock,
    advanceTo,
    pending,
    maxPending: () => maxPending,
    endByTimeout,
    phases,
    documentTarget,
    windowTarget,
    setDeadlines: (next: { idle: number; session: number } | undefined) => {
      deadlines = next === undefined ? undefined : { idleExpiresAt: next.idle, sessionExpiresAt: next.session, lastRefreshAt: T };
      for (const listener of [...deadlineListeners]) listener();
    },
    setView: (next: SessionView) => {
      view = next;
      for (const listener of [...viewListeners]) listener();
    },
    showTab: () => {
      documentTarget.dispatchEvent(new Event("visibilitychange"));
    },
  };
}

describe("the one timer (SS141)", () => {
  it("holds exactly one timer, set for the moment the idle window opens, and sets none before it is needed", () => {
    const { scheduler, pending } = setup();

    scheduler.replan();

    expect(pending.size).toBe(1);
    expect([...pending.values()][0]?.at).toBe(T + 25 * MINUTE);
    expect(scheduler.getSnapshot()).toEqual({ phase: { kind: "running" }, remainingMs: undefined });
  });

  it("never has more than one pending across 25 minutes of simulated clock, with replans in between", () => {
    const { scheduler, advanceTo, pending, maxPending } = setup();
    scheduler.replan();

    advanceTo(T + 10 * MINUTE);
    scheduler.replan();
    scheduler.replan();
    advanceTo(T + 25 * MINUTE + 30_000);

    expect(maxPending()).toBe(1);
    expect(pending.size).toBe(1);
  });

  it("replans when the deadlines change: the old timer is cleared and a new one set for the new window", () => {
    const { scheduler, pending, setDeadlines } = setup();
    scheduler.replan();

    setDeadlines({ idle: T + 20 * MINUTE, session: T + 12 * HOUR });

    expect(pending.size).toBe(1);
    expect([...pending.values()][0]?.at).toBe(T + 15 * MINUTE);
  });

  it("replans when the view changes, and holds no timer once the session is not authenticated", () => {
    const { scheduler, pending, setView } = setup();
    scheduler.replan();
    expect(pending.size).toBe(1);

    setView({ status: "anonymous", notice: "session-expired" });

    expect(pending.size).toBe(0);
    expect(scheduler.getSnapshot().phase).toEqual({ kind: "inactive" });
  });

  it("holds no timer without deadlines, and none after they are cleared (SS133)", () => {
    const { scheduler, pending, setDeadlines, advanceTo, endByTimeout } = setup();
    scheduler.replan();

    setDeadlines(undefined);
    advanceTo(T + 2 * HOUR);

    expect(pending.size).toBe(0);
    expect(endByTimeout).not.toHaveBeenCalled();
  });
});

describe("the idle warning and its countdown (SS134, SS141)", () => {
  it("opens when the timer fires at 25 minutes, with 5 minutes left", () => {
    const { scheduler, advanceTo } = setup();
    scheduler.replan();

    advanceTo(T + 25 * MINUTE - 1);
    expect(scheduler.getSnapshot().phase.kind).toBe("running");
    advanceTo(T + 25 * MINUTE);

    expect(scheduler.getSnapshot()).toEqual({ phase: { kind: "idle-warning", endsAt: T + 30 * MINUTE }, remainingMs: 5 * MINUTE });
  });

  it("updates the countdown every second with the same single timer", () => {
    const { scheduler, advanceTo, pending, maxPending } = setup();
    scheduler.replan();
    advanceTo(T + 25 * MINUTE);

    const seen: (number | undefined)[] = [];
    scheduler.subscribe(() => seen.push(scheduler.getSnapshot().remainingMs));
    advanceTo(T + 25 * MINUTE + 3000);

    expect(seen).toEqual([299_000, 298_000, 297_000]);
    expect(pending.size).toBe(1);
    expect(maxPending()).toBe(1);
  });

  it("returns the same snapshot object until the phase or the visible second changes", () => {
    const { scheduler, advanceTo, clock } = setup();
    scheduler.replan();
    advanceTo(T + 25 * MINUTE);
    const first = scheduler.getSnapshot();

    clock.now += 400;
    scheduler.replan();
    expect(scheduler.getSnapshot()).toBe(first);

    advanceTo(T + 25 * MINUTE + 1000);
    expect(scheduler.getSnapshot()).not.toBe(first);
  });

  it("SS150: never asks for anything over 25 minutes without activity: its session has no way to extend", () => {
    const { scheduler, advanceTo, endByTimeout } = setup();
    scheduler.replan();

    advanceTo(T + 29 * MINUTE);

    expect(scheduler.getSnapshot().phase.kind).toBe("idle-warning");
    expect(endByTimeout).not.toHaveBeenCalled();
  });
});

describe("expiry (SS136, SS176)", () => {
  it("ends the session by idleness when the countdown reaches the deadline, once", () => {
    const { scheduler, advanceTo, endByTimeout } = setup();
    scheduler.replan();

    advanceTo(T + 30 * MINUTE - 1);
    expect(endByTimeout).not.toHaveBeenCalled();
    advanceTo(T + 30 * MINUTE);

    expect(endByTimeout).toHaveBeenCalledExactlyOnceWith("idle");
  });

  it("ends the session by the absolute deadline with the absolute reason", () => {
    const { scheduler, advanceTo, endByTimeout } = setup({ idle: T + 20 * HOUR, session: T + 10 * MINUTE });
    scheduler.replan();

    advanceTo(T + 5 * MINUTE);
    expect(scheduler.getSnapshot().phase.kind).toBe("absolute-warning");
    advanceTo(T + 10 * MINUTE);

    expect(endByTimeout).toHaveBeenCalledExactlyOnceWith("absolute");
  });
});

describe("time that jumps (SS135, SS136)", () => {
  it("SS135: a 27 minute jump with the timers unfired shows the warning with 3 minutes left once the tab is visible", () => {
    const { scheduler, clock, showTab } = setup();
    scheduler.replan();

    clock.now = T + 27 * MINUTE;
    expect(scheduler.getSnapshot().phase.kind).toBe("running");
    showTab();

    expect(scheduler.getSnapshot()).toEqual({ phase: { kind: "idle-warning", endsAt: T + 30 * MINUTE }, remainingMs: 3 * MINUTE });
  });

  it("SS136: a 31 minute jump ends the session at once and the warning never appears", () => {
    const { scheduler, clock, showTab, endByTimeout, phases } = setup();
    scheduler.replan();

    clock.now = T + 31 * MINUTE;
    showTab();

    expect(endByTimeout).toHaveBeenCalledExactlyOnceWith("idle");
    expect(phases).not.toContain("idle-warning");
    expect(scheduler.getSnapshot().phase.kind).not.toBe("idle-warning");
  });

  it("pageshow plans again, like a tab that becomes visible", () => {
    const { scheduler, clock, windowTarget } = setup();
    scheduler.replan();

    clock.now = T + 27 * MINUTE;
    windowTarget.dispatchEvent(new Event("pageshow"));

    expect(scheduler.getSnapshot().phase.kind).toBe("idle-warning");
  });

  it("a tab that is hidden does not plan again, and plans when it is visible", () => {
    const { scheduler, clock, documentTarget, showTab } = setup();
    scheduler.replan();
    clock.now = T + 27 * MINUTE;

    documentTarget.visibilityState = "hidden";
    showTab();
    expect(scheduler.getSnapshot().phase.kind).toBe("running");

    documentTarget.visibilityState = "visible";
    showTab();
    expect(scheduler.getSnapshot().phase.kind).toBe("idle-warning");
  });
});

describe("the dismissal of the absolute warning", () => {
  it("shows no dialog after the dismissal, holds for that deadline and is forgotten with the session", () => {
    const { scheduler, advanceTo, setDeadlines } = setup({ idle: T + 20 * HOUR, session: T + 10 * MINUTE });
    scheduler.replan();
    advanceTo(T + 5 * MINUTE);
    expect(scheduler.getSnapshot().phase.kind).toBe("absolute-warning");

    scheduler.dismissAbsoluteWarning();
    expect(scheduler.getSnapshot().phase).toEqual({ kind: "absolute-acknowledged", endsAt: T + 10 * MINUTE });
    advanceTo(T + 8 * MINUTE);
    expect(scheduler.getSnapshot().phase.kind).toBe("absolute-acknowledged");

    setDeadlines({ idle: T + 20 * HOUR, session: T + 12 * MINUTE });
    expect(scheduler.getSnapshot().phase.kind).toBe("absolute-warning");

    setDeadlines(undefined);
    setDeadlines({ idle: T + 20 * HOUR, session: T + 10 * MINUTE });
    expect(scheduler.getSnapshot().phase.kind).toBe("absolute-warning");
  });

  it("a dismissal with no absolute warning open changes nothing", () => {
    const { scheduler } = setup();
    scheduler.replan();
    const before = scheduler.getSnapshot();

    scheduler.dismissAbsoluteWarning();

    expect(scheduler.getSnapshot()).toBe(before);
  });
});

describe("dispose", () => {
  it("cancels the timer and stops listening to the tab, the page and the session", () => {
    const { scheduler, pending, clock, showTab, windowTarget, setDeadlines, endByTimeout } = setup();
    scheduler.replan();
    expect(pending.size).toBe(1);

    scheduler.dispose();
    clock.now = T + 31 * MINUTE;
    showTab();
    windowTarget.dispatchEvent(new Event("pageshow"));
    setDeadlines({ idle: T + MINUTE, session: T + 2 * MINUTE });

    expect(pending.size).toBe(0);
    expect(endByTimeout).not.toHaveBeenCalled();
  });
});
