/**
 * The CSRF token of the administrative cookie routes (session-endpoints-cookie-and-csrf design.md,
 * decision 3): the HMAC that binds a double-submit token to the refresh token, and the keys it is
 * computed with. The HMAC lives here and not in {@code shared.security.token} so the exemption of
 * {@code WebLayerTokenIsolationTest} stays two classes, and not in {@code shared.web} because
 * {@code SigningAndHashingConfinementTest} forbids a MAC there.
 *
 * <p>This package carries no layer segment, like {@link com.confia.shared.security}.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumer is {@code bootstrap},
 * whose {@code AdminApplication} imports {@link com.confia.shared.security.csrf.CsrfConfiguration}.
 * The filter that verifies the token lives in {@code shared.web.csrf}, inside {@code shared}. The
 * portal and the worker never load the package.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.security.csrf;
