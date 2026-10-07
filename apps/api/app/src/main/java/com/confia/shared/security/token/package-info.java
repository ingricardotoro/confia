/**
 * The in-house compact JWS of the administrative access token (session-tokens-and-web-layer
 * design.md, decisions 1 and 2; owner decision DA-2: no JOSE or JWT library, which
 * {@code apps/api/pom.xml} bans by name): a closed-header EdDSA (Ed25519) codec over the JDK's own
 * {@code Signature}, with strict and canonical base64url, and the in-memory ring of signing keys it
 * verifies against.
 *
 * <p>This package carries no layer segment ({@code domain}, {@code application}, {@code
 * infrastructure} or {@code web}), like {@link com.confia.shared.security}: {@link
 * com.confia.architecture.LayeredArchitectureTest} treats it as outside the layered architecture,
 * and ADR-0005, check 13, allows signing utilities in {@code shared.security} and its subpackages.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumers that justify it). They are {@code bootstrap}, whose
 * {@code ConfiaApplication} and {@code AdminApplication} will check and import the key ring and its
 * configuration (task 1.2), and {@code identity}, whose session use cases will sign and verify
 * tokens (tasks 1.4 and 3.3). Nothing outside {@code shared}, {@code bootstrap} and {@code identity}
 * may depend on this package; the web layer reaches it only through the authentication filter of
 * {@code shared.web}.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.security.token;
