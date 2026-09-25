/**
 * The {@code identity} capability: staff login with a password, the anti-brute-force backoff
 * that guards it, and the audit trail both produce (ADR-0009; design.md,
 * {@code identity-module-and-password-authentication}).
 *
 * <p>This change ({@code identity-module-and-password-authentication}, F0 change 7, first of
 * three sequential parts) delivers the module's {@code domain} package only, in this cut (PR C1):
 * the two-outcome sealed authentication result, the staff account and its identifier, and the
 * login identifier value object that normalizes an email before it is looked up, hashed into a
 * fingerprint, or checked against {@code identity_staff_account.email}'s {@code CHECK}
 * (design.md, decision 10; the note on {@code LoginIdentifier}'s placement in
 * {@code apply-progress.md}). {@code application} and {@code infrastructure} — the password
 * hasher, the backoff policy's persistence, the use case itself and its jOOQ adapters — are later
 * cuts of this same change (PR C2 and PR C3), not later changes.
 *
 * <p>Out of scope, and not "prepared for": MFA and TOTP ({@code
 * mfa-totp-and-password-recovery}); session tokens, HTTP endpoints, Spring Security and the
 * per-request delay that materializes {@code AuthenticationDecision.requiredDelay()} ({@code
 * session-tokens-and-web-layer}); column-level encryption and password recovery (also {@code
 * mfa-totp-and-password-recovery}); rate limiting by IP address (change 11, named brecha in
 * design.md decision 2).
 */
package com.confia.identity;
