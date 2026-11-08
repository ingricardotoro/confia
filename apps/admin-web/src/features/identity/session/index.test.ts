import { describe, expect, it } from "vitest";
import { accessSession, currentSessionOptions, sessionKeys } from "./index.ts";
import type { EnrollmentSnapshot, EnrollmentStep, StartEnrollmentOutcome } from "./index.ts";

// admin-web-foundations-and-session, task 6.1 (part 6b): the module's own instance, as the app imports it.
describe("the session module", () => {
  it("creates one session that starts restoring and shows no token", () => {
    expect(accessSession.getView()).toEqual({ status: "restoring" });
    expect(accessSession.hasSecondFactorToken("mfa-verify")).toBe(false);
  });

  it("binds the current session query to that session", () => {
    expect(currentSessionOptions().queryKey).toEqual(sessionKeys.current());
  });

  it("offers the enrollment operations and not the old enroll, and no enrollment before a sign in", () => {
    const snapshot: EnrollmentSnapshot | undefined = accessSession.getEnrollment();
    const step: EnrollmentStep = "secret";
    const outcome: StartEnrollmentOutcome = { kind: "no-token" };

    expect(snapshot).toBeUndefined();
    expect([step, outcome.kind]).toEqual(["secret", "no-token"]);
    expect(typeof accessSession.startEnrollment).toBe("function");
    expect("enroll" in accessSession).toBe(false);
  });
});
