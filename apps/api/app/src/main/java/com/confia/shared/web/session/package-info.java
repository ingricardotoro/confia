/**
 * The cookie helper of the administrative session (session-endpoints-cookie-and-csrf design.md,
 * decision 5): the one place that knows the names and the attributes of the session cookie and of
 * the CSRF cookie, writes them with {@code ResponseCookie} and reads the session cookie with the
 * servlet {@code Cookie}. The first production {@code Set-Cookie} is the login endpoint. Only the
 * administrative process loads it. The helper has no configuration surface: nothing can turn {@code
 * Secure} off or rename a cookie ({@code SessionCookieConfigurationSurfaceTest}).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumer is {@code
 * identity.web.LoginController}, which calls {@link
 * com.confia.shared.web.session.SessionCookies#issue} when a login starts a session.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.web.session;
