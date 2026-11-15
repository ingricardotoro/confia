import { describe, expect, it } from "vitest";
import { ABSOLUTE_WARNING_LEAD_MS, ACTIVITY_CADENCE_MS, IDLE_WARNING_LEAD_MS } from "./policy.ts";
import { planLifecycle } from "./planner.ts";

// admin-web-shell-and-session-lifecycle, task 1.6 (design.md H2): the three constants the client fixes
// until the contract exposes them (assumption D4), named and with the values of the working assumptions.
describe("the lifecycle policy", () => {
  it("warns 5 minutes before each deadline and lets activity refresh at most once a minute", () => {
    expect(IDLE_WARNING_LEAD_MS).toBe(5 * 60 * 1000);
    expect(ABSOLUTE_WARNING_LEAD_MS).toBe(5 * 60 * 1000);
    expect(ACTIVITY_CADENCE_MS).toBe(60 * 1000);
  });

  it("is what the planner takes: the idle warning opens at the idle lead and not a millisecond earlier", () => {
    const policy = { idleWarningLeadMs: IDLE_WARNING_LEAD_MS, absoluteWarningLeadMs: ABSOLUTE_WARNING_LEAD_MS };
    const idleExpiresAt = Date.parse("2026-11-10T10:30:00Z");
    const sessionExpiresAt = Date.parse("2026-11-10T22:00:00Z");
    const at = (now: number) => planLifecycle({ deadlines: { idleExpiresAt, sessionExpiresAt }, now, absoluteDismissedFor: undefined }, policy);

    expect(at(idleExpiresAt - IDLE_WARNING_LEAD_MS - 1).phase.kind).toBe("running");
    expect(at(idleExpiresAt - IDLE_WARNING_LEAD_MS).phase.kind).toBe("idle-warning");
  });
});
