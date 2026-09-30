package com.confia.identity.testsupport.fixture;

/**
 * Permanent, deliberate leak fixture — never production code (column-encryption-and-mfa-totp
 * design.md, decision 9). A {@code record} carrying a secret in a bare {@code String} and
 * <b>without</b> an overridden {@code toString()}: exactly the shape that caused part 1's third
 * blocking finding, where {@code AuthenticationCommand} printed a password in clear through its
 * compiler-generated {@code toString()}.
 *
 * <p>{@code IdentitySecretRedactionIT#theRedactionSweepDetectsARealLeak} uses it to prove the
 * redaction sweep is able to detect a real leak, not merely to find none because it never looked
 * (the same spirit ADR-0018 demands of every ArchUnit rule: a rule that never rejects anything
 * protects nothing). The value it is built with is a fake string, never a real secret.
 */
public record LeakingMfaSecretFixture(String fakeSecretForTestingOnly) {
}
