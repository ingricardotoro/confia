/**
 * The planner of the session lifecycle (design.md H2): a pure function from the deadlines, an instant and
 * the dismissal of the absolute warning to the phase of the session and the next instant at which that
 * phase, or the countdown it shows, can change. It imports nothing from outside its folder (the rule
 * `admin-session-lifecycle-planner-is-pure`): no React, no `shared/api`, no DOM and no timer.
 */

/** How long before each deadline its warning opens. */
export interface LifecyclePolicy {
  readonly idleWarningLeadMs: number;
  readonly absoluteWarningLeadMs: number;
}

export interface PlanInput {
  /** Local milliseconds, already corrected by the server clock; `undefined` before any answer or after the session ended. */
  readonly deadlines: { readonly idleExpiresAt: number; readonly sessionExpiresAt: number } | undefined;
  readonly now: number;
  /** The `sessionExpiresAt` for which the person dismissed the absolute warning, if they did. */
  readonly absoluteDismissedFor: number | undefined;
}

export type LifecyclePhase =
  | { readonly kind: "inactive" }
  | { readonly kind: "running" }
  | { readonly kind: "idle-warning"; readonly endsAt: number }
  | { readonly kind: "absolute-warning"; readonly endsAt: number }
  | { readonly kind: "absolute-acknowledged"; readonly endsAt: number }
  | { readonly kind: "expired"; readonly reason: "idle" | "absolute" };

export interface LifecyclePlan {
  readonly phase: LifecyclePhase;
  /** The next instant to plan again, or `undefined` when nothing will change by itself. */
  readonly wakeAt: number | undefined;
}

const SECOND_MS = 1000;

/** The next instant at which the whole seconds left before `endsAt` change; never later than `endsAt`. */
function nextCountdownSecond(now: number, endsAt: number): number {
  const remainder = (endsAt - now) % SECOND_MS;
  return now + (remainder === 0 ? SECOND_MS : remainder);
}

export function planLifecycle(input: PlanInput, policy: LifecyclePolicy): LifecyclePlan {
  const { deadlines, now, absoluteDismissedFor } = input;
  if (deadlines === undefined) return { phase: { kind: "inactive" }, wakeAt: undefined };
  const { idleExpiresAt: idle, sessionExpiresAt: absolute } = deadlines;

  if (now >= absolute) return { phase: { kind: "expired", reason: "absolute" }, wakeAt: undefined };
  if (now >= idle) return { phase: { kind: "expired", reason: "idle" }, wakeAt: undefined };

  // The session ends at the earlier deadline, so that is the one a countdown counts down to (SS132).
  const endsAt = Math.min(idle, absolute);
  const absoluteWindowOpensAt = absolute - policy.absoluteWarningLeadMs;
  if (now >= absoluteWindowOpensAt) {
    // The absolute warning wins over the idle one, also after it was dismissed (SS175).
    if (absoluteDismissedFor === absolute) return { phase: { kind: "absolute-acknowledged", endsAt }, wakeAt: endsAt };
    return { phase: { kind: "absolute-warning", endsAt }, wakeAt: nextCountdownSecond(now, endsAt) };
  }

  const idleWindowOpensAt = idle - policy.idleWarningLeadMs;
  if (now >= idleWindowOpensAt) {
    return { phase: { kind: "idle-warning", endsAt: idle }, wakeAt: nextCountdownSecond(now, idle) };
  }
  return { phase: { kind: "running" }, wakeAt: Math.min(idleWindowOpensAt, absoluteWindowOpensAt) };
}
