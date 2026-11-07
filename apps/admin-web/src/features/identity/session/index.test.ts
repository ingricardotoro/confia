import { describe, expect, it } from "vitest";
import { accessSession, currentSessionOptions, sessionKeys } from "./index.ts";

// admin-web-foundations-and-session, task 6.1 (part 6b): the module's own instance, as the app imports it.
describe("the session module", () => {
  it("creates one session that starts restoring and shows no token", () => {
    expect(accessSession.getView()).toEqual({ status: "restoring" });
    expect(accessSession.hasSecondFactorToken("mfa-verify")).toBe(false);
  });

  it("binds the current session query to that session", () => {
    expect(currentSessionOptions().queryKey).toEqual(sessionKeys.current());
  });
});
