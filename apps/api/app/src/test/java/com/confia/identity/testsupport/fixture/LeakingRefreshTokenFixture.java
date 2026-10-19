package com.confia.identity.testsupport.fixture;

/**
 * Permanent, deliberate leak fixture, never production code (session-tokens-and-web-layer, scenario
 * I86). A {@code record} carrying a refresh token in a bare {@code String} and <b>without</b> an
 * overridden {@code toString()}: exactly the shape the redacted {@code PlainRefreshToken},
 * {@code IssuedSession} and {@code EncryptedSuccessor} exist to avoid.
 *
 * <p>{@code SessionSecretsNotObservableTest} feeds its text to the same sweep that checks the real
 * objects, to prove that the sweep can detect a real leak and does not pass merely because it never
 * looked. The value it is built with is a freshly generated token, never a real one.
 */
public record LeakingRefreshTokenFixture(String tokenForTestingOnly) {
}
