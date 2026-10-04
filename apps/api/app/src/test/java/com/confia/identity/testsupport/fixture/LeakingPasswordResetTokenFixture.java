package com.confia.identity.testsupport.fixture;

/**
 * Permanent, deliberate leak fixture — never production code (password-recovery-token design.md,
 * decision 10). A {@code record} carrying a password-reset token in a bare {@code String} and
 * <b>without</b> an overridden {@code toString()}: exactly the shape the redacted {@code
 * PlainPasswordResetToken} and {@code ResetPasswordCommand} exist to avoid.
 *
 * <p>{@code IdentitySecretRedactionIT#thePasswordResetSweepDetectsARealLeakOfTheToken} uses it to
 * prove the password-reset sweep can detect a real leak, not merely find none because it never
 * looked (the second scenario of the requirement "Ningún secreto observable"). The value it is
 * built with is a freshly generated token, never a real one.
 */
public record LeakingPasswordResetTokenFixture(String tokenForTestingOnly) {
}
