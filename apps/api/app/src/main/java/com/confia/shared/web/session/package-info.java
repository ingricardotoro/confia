/**
 * The cookie helper of the administrative session (session-endpoints-cookie-and-csrf design.md,
 * decision 5): the one place that knows the names and the attributes of the session cookie and of
 * the CSRF cookie, writes them with {@code ResponseCookie} and reads the session cookie with the
 * servlet {@code Cookie}. No controller calls it yet: the first production {@code Set-Cookie} is
 * the login endpoint. Only the administrative process loads it.
 */
package com.confia.shared.web.session;
