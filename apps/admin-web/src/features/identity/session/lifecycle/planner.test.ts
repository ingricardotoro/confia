import { describe, expect, it } from "vitest";
import { planLifecycle, type LifecyclePhase, type LifecyclePolicy } from "./planner.ts";

// admin-web-shell-and-session-lifecycle, task 1.4 (SS134, SS175, design.md H2). The planner is a pure
// function of the deadlines, an instant and the dismissal: every row of the table of H2 is tried at its
// exact boundary, 1 ms before and 1 ms after. Every instant is a fixed number.
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const POLICY: LifecyclePolicy = { idleWarningLeadMs: 5 * MINUTE, absoluteWarningLeadMs: 5 * MINUTE };
const T = Date.parse("2026-11-10T10:00:00Z");

/** Idle deadline 30 minutes out and absolute deadline 12 hours out, unless a test says otherwise. */
const I = T + 30 * MINUTE;
const S = T + 12 * HOUR;

function plan(now: number, deadlines: { idle?: number; session?: number } = {}, absoluteDismissedFor?: number) {
  return planLifecycle(
    {
      deadlines: { idleExpiresAt: deadlines.idle ?? I, sessionExpiresAt: deadlines.session ?? S },
      now,
      absoluteDismissedFor,
    },
    POLICY,
  );
}

const kind = (phase: LifecyclePhase) => phase.kind;

describe("without deadlines", () => {
  it("is inactive and has nothing to wake for", () => {
    expect(planLifecycle({ deadlines: undefined, now: T, absoluteDismissedFor: undefined }, POLICY)).toEqual({
      phase: { kind: "inactive" },
      wakeAt: undefined,
    });
  });
});

describe("running", () => {
  it("is running far from both windows and wakes when the first window opens", () => {
    expect(plan(T)).toEqual({ phase: { kind: "running" }, wakeAt: I - 5 * MINUTE });
  });

  it("wakes for the absolute window when it opens before the idle one", () => {
    const session = T + 20 * MINUTE;
    expect(plan(T, { session })).toEqual({ phase: { kind: "running" }, wakeAt: session - 5 * MINUTE });
  });

  it("SS134: is still running 1 ms before the idle window", () => {
    expect(kind(plan(I - 5 * MINUTE - 1).phase)).toBe("running");
  });
});

describe("idle-warning (SS134)", () => {
  it("opens exactly 5 minutes before the idle deadline", () => {
    const { phase } = plan(I - 5 * MINUTE);
    expect(phase).toEqual({ kind: "idle-warning", endsAt: I });
  });

  it("is open 1 ms after the window opened", () => {
    expect(kind(plan(I - 5 * MINUTE + 1).phase)).toBe("idle-warning");
  });

  it("is still open 1 ms before the deadline", () => {
    expect(plan(I - 1).phase).toEqual({ kind: "idle-warning", endsAt: I });
  });

  it("wakes at the next whole second of the countdown", () => {
    expect(plan(I - 5 * MINUTE).wakeAt).toBe(I - 5 * MINUTE + 1000);
    expect(plan(I - 5 * MINUTE + 1).wakeAt).toBe(I - 5 * MINUTE + 1000);
    expect(plan(I - 5 * MINUTE + 999).wakeAt).toBe(I - 5 * MINUTE + 1000);
    expect(plan(I - 5 * MINUTE + 1000).wakeAt).toBe(I - 5 * MINUTE + 2000);
  });

  it("never wakes after the deadline", () => {
    expect(plan(I - 1).wakeAt).toBe(I);
    expect(plan(I - 1500).wakeAt).toBe(I - 1000);
  });
});

describe("expired by idleness", () => {
  it("is expired exactly at the idle deadline", () => {
    expect(plan(I)).toEqual({ phase: { kind: "expired", reason: "idle" }, wakeAt: undefined });
  });

  it("is expired 1 ms after and not 1 ms before", () => {
    expect(plan(I + 1).phase).toEqual({ kind: "expired", reason: "idle" });
    expect(kind(plan(I - 1).phase)).toBe("idle-warning");
  });

  it("goes straight to expired when the time jumped past the deadline, with no warning (SS136)", () => {
    expect(plan(I + 31 * MINUTE).phase).toEqual({ kind: "expired", reason: "idle" });
  });
});

describe("absolute-warning", () => {
  const edge = S - 5 * MINUTE;

  it("opens exactly 5 minutes before the absolute deadline", () => {
    expect(plan(edge, { idle: S + HOUR }).phase).toEqual({ kind: "absolute-warning", endsAt: S });
  });

  it("is not open 1 ms before and is open 1 ms after", () => {
    expect(kind(plan(edge - 1, { idle: S + HOUR }).phase)).toBe("running");
    expect(kind(plan(edge + 1, { idle: S + HOUR }).phase)).toBe("absolute-warning");
  });

  it("is still open 1 ms before the deadline and wakes at the next whole second", () => {
    expect(plan(S - 1, { idle: S + HOUR })).toEqual({ phase: { kind: "absolute-warning", endsAt: S }, wakeAt: S });
    expect(plan(edge, { idle: S + HOUR }).wakeAt).toBe(edge + 1000);
  });

  it("SS175: wins over the idle warning while both windows are open", () => {
    const now = S - 4 * MINUTE;
    const idle = S - 3 * MINUTE;

    expect(plan(now, { idle }).phase).toEqual({ kind: "absolute-warning", endsAt: idle });
  });

  it("counts down to the deadline that comes first, because that one ends the session (SS132)", () => {
    expect(plan(S - 4 * MINUTE, { idle: S - 2 * MINUTE }).phase).toEqual({ kind: "absolute-warning", endsAt: S - 2 * MINUTE });
    expect(plan(S - 4 * MINUTE, { idle: S + 2 * MINUTE }).phase).toEqual({ kind: "absolute-warning", endsAt: S });
  });
});

describe("expired by the absolute deadline", () => {
  it("is expired exactly at the absolute deadline, with the absolute reason", () => {
    expect(plan(S, { idle: S + HOUR })).toEqual({ phase: { kind: "expired", reason: "absolute" }, wakeAt: undefined });
  });

  it("is expired 1 ms after and still warning 1 ms before", () => {
    expect(plan(S + 1, { idle: S + HOUR }).phase).toEqual({ kind: "expired", reason: "absolute" });
    expect(kind(plan(S - 1, { idle: S + HOUR }).phase)).toBe("absolute-warning");
  });

  it("takes the absolute reason when both deadlines have passed", () => {
    expect(plan(S + MINUTE, { idle: S - MINUTE }).phase).toEqual({ kind: "expired", reason: "absolute" });
  });

  it("takes the idle reason when only the idle deadline has passed inside the absolute window", () => {
    expect(plan(S - 3 * MINUTE, { idle: S - 3 * MINUTE }).phase).toEqual({ kind: "expired", reason: "idle" });
  });
});

describe("absolute-acknowledged", () => {
  const edge = S - 5 * MINUTE;

  it("is the phase after the person dismissed the warning for this deadline, with no dialog", () => {
    expect(plan(edge + MINUTE, { idle: S + HOUR }, S)).toEqual({
      phase: { kind: "absolute-acknowledged", endsAt: S },
      wakeAt: S,
    });
  });

  it("the dismissal holds for one deadline: when S changes, the warning is back", () => {
    expect(kind(plan(edge + MINUTE, { idle: S + HOUR }, S - 1).phase)).toBe("absolute-warning");
    expect(kind(plan(edge + MINUTE, { idle: S + HOUR }, S + 1).phase)).toBe("absolute-warning");
  });

  it("does not matter before the window opens", () => {
    expect(kind(plan(edge - 1, { idle: S + HOUR }, S).phase)).toBe("running");
  });

  it("SS175: never lets the idle warning appear after the absolute one was dismissed", () => {
    const idle = S - 2 * MINUTE;

    expect(plan(S - 4 * MINUTE, { idle }, S).phase).toEqual({ kind: "absolute-acknowledged", endsAt: idle });
    expect(plan(S - 3 * MINUTE, { idle }, S).wakeAt).toBe(idle);
  });

  it("still ends the session at the deadline", () => {
    expect(plan(S, { idle: S + HOUR }, S).phase).toEqual({ kind: "expired", reason: "absolute" });
  });
});

describe("the policy is read, not assumed", () => {
  it("moves the windows with the leads", () => {
    const wide = { idleWarningLeadMs: 10 * MINUTE, absoluteWarningLeadMs: 1 * MINUTE };
    const at = (now: number, idleExpiresAt: number) =>
      planLifecycle({ deadlines: { idleExpiresAt, sessionExpiresAt: S }, now, absoluteDismissedFor: undefined }, wide);

    expect(kind(at(I - 10 * MINUTE - 1, I).phase)).toBe("running");
    expect(kind(at(I - 10 * MINUTE, I).phase)).toBe("idle-warning");
    // An idle deadline after the absolute one, so only the absolute window is in play.
    expect(kind(at(S - MINUTE - 1, S + HOUR).phase)).toBe("running");
    expect(kind(at(S - MINUTE, S + HOUR).phase)).toBe("absolute-warning");
  });
});
