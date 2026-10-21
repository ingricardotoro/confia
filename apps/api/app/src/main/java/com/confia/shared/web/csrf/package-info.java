/**
 * The {@code Origin} and CSRF filter of the administrative chain and what it is configured with
 * (session-endpoints-cookie-and-csrf design.md, decisions 2 and 4): the list of allowed origins, the
 * routes whose credential is a cookie, and the filter itself, which is built with {@code new} in the
 * security configuration and is not a bean. The HMAC it verifies lives in {@code
 * com.confia.shared.security.csrf}.
 */
package com.confia.shared.web.csrf;
