import { describe, expect, it } from "vitest";
import type { LoginOutcome } from "../../../shared/api/auth-operations.ts";
import {
  authenticatedSession,
  secondFactorEnrollmentRequired,
  secondFactorRequired,
} from "../../../test/contract-responses.ts";
import { createTokenStore } from "./token-store.ts";

// admin-web-foundations-and-session, task 6.1 (part 6a): the tokens live in module memory and nowhere
// else (CLAUDE.md rules 9 to 11). The store takes a contract response as it came and keeps the token and
// when it stops being valid; it has no way to take an identifier at all (SS59).

const outcome = (response: { readonly body: unknown }): LoginOutcome => response.body as LoginOutcome;

describe("the token store", () => {
  it("starts with no token of either kind", () => {
    const store = createTokenStore();

    expect(store.accessToken()).toBeUndefined();
    expect(store.secondFactorToken()).toBeUndefined();
  });

  it("keeps the access token and its expiry as an instant", () => {
    const store = createTokenStore();
    const body = outcome(authenticatedSession({ now: new Date("2026-11-06T10:00:00Z") }));

    store.accept(body);

    expect(store.accessToken()).toEqual({
      token: (body as { accessToken: string }).accessToken,
      expiresAt: Date.parse("2026-11-06T10:15:00Z"),
    });
  });

  it("keeps the restricted token with the use its status names", () => {
    const store = createTokenStore();
    const verify = outcome(secondFactorRequired());
    const enroll = outcome(secondFactorEnrollmentRequired());

    store.accept(verify);
    expect(store.secondFactorToken()).toMatchObject({ use: "mfa-verify" });

    store.accept(enroll);
    expect(store.secondFactorToken()).toMatchObject({
      use: "mfa-enroll",
      token: (enroll as { secondFactorToken: string }).secondFactorToken,
    });
  });

  it("holds one kind at a time: a restricted token replaces the access token and the other way round", () => {
    const store = createTokenStore();

    store.accept(outcome(authenticatedSession()));
    store.accept(outcome(secondFactorRequired()));
    expect(store.accessToken()).toBeUndefined();
    expect(store.secondFactorToken()).toBeDefined();

    store.accept(outcome(authenticatedSession()));
    expect(store.secondFactorToken()).toBeUndefined();
    expect(store.accessToken()).toBeDefined();
  });

  it("forgets everything on clear", () => {
    const store = createTokenStore();
    store.accept(outcome(authenticatedSession()));

    store.clear();

    expect(store.accessToken()).toBeUndefined();
    expect(store.secondFactorToken()).toBeUndefined();
  });

  it("SS59: after an authenticated and a second-factor-required answer the store keeps no identifier", () => {
    const identifier = `ana.${crypto.randomUUID()}@colegio.edu.hn`;
    const store = createTokenStore();

    // The identifier was typed, but the store has no entry for it: whatever it can be asked, none of it
    // holds the value, and its whole surface is the token operations.
    store.accept(outcome(authenticatedSession()));
    const afterAuthenticated = JSON.stringify([store.accessToken(), store.secondFactorToken()]);
    store.accept(outcome(secondFactorRequired()));
    const afterSecondFactor = JSON.stringify([store.accessToken(), store.secondFactorToken()]);

    expect(afterAuthenticated).not.toContain(identifier);
    expect(afterSecondFactor).not.toContain(identifier);
    expect(Object.keys(store).sort()).toEqual(["accept", "accessToken", "clear", "secondFactorToken"]);
  });
});
