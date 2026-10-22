package com.confia.shared.web.session;

import com.confia.shared.security.csrf.CsrfTokens;
import com.confia.shared.web.csrf.CookieCredentialRoute;
import com.confia.shared.web.csrf.CsrfOriginFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Issues, rotates, clears and reads the two cookies of an administrative session
 * (session-endpoints-cookie-and-csrf design.md, decision 5). It is the only production class that
 * knows their names and attributes and the only one that depends on {@code
 * jakarta.servlet.http.Cookie}, and only to read; it writes with {@link ResponseCookie}, which
 * serializes {@code SameSite}.
 *
 * <ul>
 *   <li>{@code __Secure-confia_admin_sid} holds the refresh token (43 characters) with {@code
 *       HttpOnly}, {@code Secure}, {@code SameSite=Strict} and {@code Path=/api/v1/auth/sessions};
 *   <li>{@code __Host-confia_csrf} holds the CSRF token bound to that refresh token with {@code
 *       Secure}, {@code SameSite=Strict} and {@code Path=/}, and is readable by the page.
 * </ul>
 *
 * <p>Neither has {@code Domain}, {@code Max-Age} or {@code Expires}: both end with the browser
 * session. Both are always {@code Secure}: the prefixes require it, and there is no setting that
 * turns it off (decision D-N1). Issuing and rotating are the same operation, so the two cookies are
 * always written together and the CSRF token always matches the refresh token beside it. Clearing
 * writes the same two names with an empty value, {@code Max-Age=0} and the same {@code Path},
 * {@code Secure}, {@code HttpOnly} and {@code SameSite}, because a browser replaces a cookie only
 * when name, domain and path match.
 *
 * <p>Nothing here logs: a refresh token and a CSRF token are secrets (CLAUDE.md, regla 11). A bean
 * of the administrative process, registered by {@code SessionCookieConfiguration}. No controller
 * calls it in the change that introduced it.
 */
public final class SessionCookies {

    /** The refresh token as the session change issues it: 43 base64url characters. */
    private static final Pattern REFRESH_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final String SAME_SITE = "Strict";

    private final CsrfTokens tokens;

    public SessionCookies(CsrfTokens tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /**
     * Writes the session cookie with {@code refreshToken} and the CSRF cookie computed for it, as two
     * {@code Set-Cookie} headers added to the ones the response already has.
     *
     * @throws IllegalArgumentException when the text is not a refresh token; nothing is written and
     *     the message never repeats the text
     */
    public void issue(HttpServletResponse response, String refreshToken) {
        Objects.requireNonNull(refreshToken, "refreshToken");
        if (!REFRESH_TOKEN.matcher(refreshToken).matches()) {
            throw new IllegalArgumentException("the session cookie takes a refresh token of 43 "
                    + "base64url characters");
        }
        response.addHeader(HttpHeaders.SET_COOKIE, session(refreshToken).build().toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                csrf(tokens.issue(refreshToken)).build().toString());
    }

    /** Writes both cookies with an empty value and {@code Max-Age=0}, so a browser drops them. */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, session("").maxAge(0).build().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, csrf("").maxAge(0).build().toString());
    }

    /** What the request carries as its session cookie. */
    public SessionCookie read(HttpServletRequest request) {
        Cookie[] all = request.getCookies();
        if (all == null) {
            return new SessionCookie.Absent();
        }
        String found = null;
        int count = 0;
        for (Cookie cookie : all) {
            if (CsrfOriginFilter.SESSION_COOKIE.equals(cookie.getName())) {
                found = cookie.getValue();
                count++;
            }
        }
        if (count == 0) {
            return new SessionCookie.Absent();
        }
        return count == 1 ? new SessionCookie.Present(found == null ? "" : found)
                : new SessionCookie.Duplicated();
    }

    private static ResponseCookie.ResponseCookieBuilder session(String value) {
        return ResponseCookie.from(CsrfOriginFilter.SESSION_COOKIE, value).httpOnly(true)
                .secure(true).sameSite(SAME_SITE).path(CookieCredentialRoute.SESSIONS_PREFIX);
    }

    private static ResponseCookie.ResponseCookieBuilder csrf(String value) {
        return ResponseCookie.from(CsrfOriginFilter.CSRF_COOKIE, value).httpOnly(false)
                .secure(true).sameSite(SAME_SITE).path("/");
    }
}
