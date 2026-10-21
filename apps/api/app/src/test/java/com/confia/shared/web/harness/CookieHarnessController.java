package com.confia.shared.web.harness;

import com.confia.shared.security.csrf.CsrfTokens;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cookie harness of condition 3 (session-endpoints-cookie-and-csrf design.md, decision 2; tasks
 * 1.1). It lives only in the test tree: no production class emits {@code Set-Cookie} in this change
 * until the login endpoint of task 3.1. {@code /test/cookie/issue} sets a session cookie and a CSRF
 * cookie the way the real endpoints will, with a {@link ResponseCookie} of its own, and the two
 * cookie routes stand in for the refresh and the current-session end: they count their invocations
 * and answer {@code 200}, so a response that is not {@code 403} proves the filter let the request
 * through. {@code /test/cookie/other} is an unsafe route that is not a cookie route.
 */
@RestController
class CookieHarnessController {

    static final String SESSION_COOKIE = "__Secure-confia_admin_sid";
    static final String CSRF_COOKIE = "__Host-confia_csrf";

    private final SecureRandom random = new SecureRandom();
    private final Calls calls;
    private final CsrfTokens tokens;

    CookieHarnessController(Calls calls, CsrfTokens tokens) {
        this.calls = calls;
        this.tokens = tokens;
    }

    /** Public for {@code GET}: issues a fresh session cookie and the CSRF cookie bound to it. */
    @GetMapping("/test/cookie/issue")
    String issue(HttpServletResponse response) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION_COOKIE, refresh)
                .httpOnly(true).secure(true).sameSite("Strict").path("/test/cookie").build()
                .toString());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(CSRF_COOKIE,
                tokens.issue(refresh)).secure(true).sameSite("Strict").path("/").build()
                .toString());
        return "issued";
    }

    /** Stand-in for the refresh: a cookie route, public for {@code POST} in the harness. */
    @RequestMapping(path = "/test/cookie/refresh", method = RequestMethod.POST)
    String refresh(HttpServletRequest request) {
        calls.cookieRouteInvoked();
        return "refreshed:" + countOf(request, SESSION_COOKIE) + ":" + countOf(request, CSRF_COOKIE);
    }

    /** Stand-in for the current-session end: a cookie route, public for {@code DELETE}. */
    @RequestMapping(path = "/test/cookie/current", method = RequestMethod.DELETE)
    String endCurrent(HttpServletRequest request) {
        calls.cookieRouteInvoked();
        return "ended:" + countOf(request, SESSION_COOKIE) + ":" + countOf(request, CSRF_COOKIE);
    }

    /** An unsafe route that is not a cookie route: only the {@code Origin} rule applies. */
    @RequestMapping(path = "/test/cookie/other", method = {RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE})
    String other() {
        calls.cookieRouteInvoked();
        return "other";
    }

    private static long countOf(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        return cookies == null ? 0 : Arrays.stream(cookies).filter(c -> c.getName().equals(name)).count();
    }
}
