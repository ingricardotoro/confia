package com.confia.identity.application;

/**
 * "Request received", and nothing else (password-recovery-token design.md decision 6;
 * specs/identity/spec.md, "Prohibición de enumeración de usuarios"). It has no components, so the
 * result for an existing account, for an address with no account and for an account at its hourly
 * limit is identical by construction. It is not an HTTP status and carries no user-facing text: the
 * {@code 202 Accepted} and its message belong to {@code session-tokens-and-web-layer}.
 */
public record RequestPasswordResetDecision() {
}
