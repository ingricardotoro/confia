import type { AccessSession } from "../access-session.ts";
import { planLifecycle, type LifecyclePhase, type LifecyclePolicy } from "./planner.ts";

/** What of the session the scheduler reads and the one thing it asks of it: to end by expiry. It can never ask for a refresh. */
export type SchedulerSession = Pick<AccessSession, "getView" | "subscribe" | "getDeadlines" | "subscribeDeadlines" | "endByTimeout">;

/** The timers of the page, injected so a test moves time by hand. */
export interface Timers {
  setTimeout(callback: () => void, ms: number): number;
  clearTimeout(handle: number): void;
}

export interface LifecycleSnapshot {
  readonly phase: LifecyclePhase;
  /** The time left in a phase with a dialog; `undefined` in the others. */
  readonly remainingMs: number | undefined;
}

/** Function properties, not methods: `usePhase` hands `subscribe` and `getSnapshot` to React unbound. */
export interface LifecycleScheduler {
  readonly subscribe: (listener: () => void) => () => void;
  /** The same object until the phase or the visible remaining second changes. */
  readonly getSnapshot: () => LifecycleSnapshot;
  readonly dismissAbsoluteWarning: () => void;
  readonly replan: () => void;
  readonly dispose: () => void;
}

export interface SchedulerDependencies {
  readonly session: SchedulerSession;
  readonly timers: Timers;
  readonly now: () => number;
  readonly policy: LifecyclePolicy;
  /** `document`: a tab that becomes visible plans again. */
  readonly document: EventTarget & { readonly visibilityState: DocumentVisibilityState };
  /** `window`: a page restored from the back-forward cache plans again. */
  readonly window: EventTarget;
}

const SECOND_MS = 1000;

/** What a person can see of a snapshot: the phase and the whole seconds left. Two snapshots with the same key look the same. */
function shownKey(phase: LifecyclePhase, remainingMs: number | undefined): string {
  const endsAt = "endsAt" in phase ? String(phase.endsAt) : "";
  const reason = "reason" in phase ? phase.reason : "";
  const seconds = remainingMs === undefined ? "" : String(Math.ceil(remainingMs / SECOND_MS));
  return `${phase.kind}|${endsAt}|${reason}|${seconds}`;
}

/**
 * The scheduler of the session lifecycle (design.md H2, section 7): at most one pending timer, set for the
 * next instant the planner names. It plans again when the deadlines or the view change, when a tab becomes
 * visible and when a page is restored, always from absolute instants, so a delayed timer or a suspended
 * machine changes nothing. It changes only what the interface shows; when the session expires it asks the
 * session to end by timeout, and it never asks for a refresh.
 */
export function createLifecycleScheduler({ session, timers, now, policy, document, window }: SchedulerDependencies): LifecycleScheduler {
  let snapshot: LifecycleSnapshot = { phase: { kind: "inactive" }, remainingMs: undefined };
  let key = shownKey(snapshot.phase, snapshot.remainingMs);
  let handle: number | undefined;
  /** The `sessionExpiresAt` the person dismissed the absolute warning for. It lives here and nowhere else. */
  let dismissedFor: number | undefined;
  let disposed = false;
  const listeners = new Set<() => void>();

  function publish(phase: LifecyclePhase, at: number): void {
    const remainingMs = phase.kind === "idle-warning" || phase.kind === "absolute-warning" ? phase.endsAt - at : undefined;
    const next = shownKey(phase, remainingMs);
    if (next === key) return;
    key = next;
    snapshot = { phase, remainingMs };
    for (const listener of [...listeners]) listener();
  }

  function replan(): void {
    if (disposed) return;
    if (handle !== undefined) {
      timers.clearTimeout(handle);
      handle = undefined;
    }
    const deadlines = session.getView().status === "authenticated" ? session.getDeadlines() : undefined;
    if (deadlines === undefined) dismissedFor = undefined;
    const at = now();
    const plan = planLifecycle({ deadlines, now: at, absoluteDismissedFor: dismissedFor }, policy);
    if (plan.phase.kind === "expired") {
      // The session ends first and tells its listeners, which plan again and find nothing left to plan.
      session.endByTimeout(plan.phase.reason);
      return;
    }
    publish(plan.phase, at);
    if (plan.wakeAt !== undefined) handle = timers.setTimeout(replan, Math.max(0, plan.wakeAt - at));
  }

  function onVisibilityChange(): void {
    if (document.visibilityState === "visible") replan();
  }

  const stops = [session.subscribe(replan), session.subscribeDeadlines(replan)];
  document.addEventListener("visibilitychange", onVisibilityChange);
  window.addEventListener("pageshow", replan);
  replan();

  return {
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    getSnapshot: () => snapshot,
    dismissAbsoluteWarning() {
      const deadlines = session.getDeadlines();
      if (snapshot.phase.kind !== "absolute-warning" || deadlines === undefined) return;
      dismissedFor = deadlines.sessionExpiresAt;
      replan();
    },
    replan,
    dispose() {
      disposed = true;
      if (handle !== undefined) timers.clearTimeout(handle);
      handle = undefined;
      for (const stop of stops) stop();
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.removeEventListener("pageshow", replan);
      listeners.clear();
    },
  };
}
