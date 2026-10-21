package com.confia.shared.web.csrf;

import com.confia.shared.security.csrf.CsrfTokens;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The CSRF and {@code Origin} defence of the administrative chain (session-endpoints-cookie-and-csrf
 * design.md, decision 2; hard condition 3). It stands before the token filter, so a request it
 * refuses verifies no token and spends no quota. The first rule that fails decides, with {@code 403}
 * and {@code csrf-invalid}, written without following the chain:
 *
 * <ol>
 *   <li>a safe method ({@code GET}, {@code HEAD}, {@code OPTIONS}, {@code TRACE}) passes unchecked;
 *   <li>every other method needs exactly one {@code Origin} header whose value is exactly one of the
 *       allowed origins (missing, {@code null}, repeated or foreign is refused);
 *   <li>a cookie route that carries the session cookie needs exactly one CSRF cookie and exactly one
 *       {@code X-CSRF-Token} header, equal to each other, with a valid HMAC for the refresh token
 *       held in the session cookie; two session cookies are refused, never choosing one;
 *   <li>a cookie route without the session cookie passes: there is no ambient credential to protect
 *       (the controller answers {@code 401} on a refresh and {@code 204} on an end of session).
 * </ol>
 *
 * <p>The response never says which rule failed, and the filter logs nothing: not a header, a cookie
 * nor an origin. It is built with {@code new} and is not a bean, like the token filter.
 *
 * <p>The cookies are read from the raw {@code Cookie} headers, so that {@code jakarta.servlet.http.
 * Cookie} stays confined to the cookie helper of the session change (WebEdgeScopeExclusionInventoryTest);
 * a header split in several lines, as HTTP/2 does, is read as the cookies it carries.
 */
public final class CsrfOriginFilter extends OncePerRequestFilter {

    /** The session cookie: the refresh token. */
    public static final String SESSION_COOKIE = "__Secure-confia_admin_sid";

    /** The CSRF cookie the single-page application reads. */
    public static final String CSRF_COOKIE = "__Host-confia_csrf";

    /** The header that repeats the CSRF cookie. */
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AllowedOrigins origins;
    private final List<CookieCredentialRoute> cookieRoutes;
    private final CsrfTokens tokens;
    private final ProblemResponses problems;

    public CsrfOriginFilter(AllowedOrigins origins, List<CookieCredentialRoute> cookieRoutes,
            CsrfTokens tokens, ProblemResponses problems) {
        this.origins = origins;
        this.cookieRoutes = List.copyOf(cookieRoutes);
        this.tokens = tokens;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod()) || accepts(request)) {
            chain.doFilter(request, response);
            return;
        }
        problems.write(request, response, ProblemCode.CSRF_INVALID);
    }

    private boolean accepts(HttpServletRequest request) {
        List<String> origin = Collections.list(request.getHeaders("Origin"));
        if (origin.size() != 1 || !origins.contains(origin.get(0))) {
            return false;
        }
        if (cookieRoutes.stream().noneMatch(route -> route.matches(request))) {
            return true;
        }
        List<String> sessions = cookiesNamed(request, SESSION_COOKIE);
        if (sessions.isEmpty()) {
            return true;
        }
        List<String> csrfCookies = cookiesNamed(request, CSRF_COOKIE);
        List<String> csrfHeaders = Collections.list(request.getHeaders(CSRF_HEADER));
        return sessions.size() == 1 && csrfCookies.size() == 1 && csrfHeaders.size() == 1
                && tokens.verifyDoubleSubmit(csrfCookies.get(0), csrfHeaders.get(0),
                        sessions.get(0));
    }

    /** The values of every cookie called {@code name}, over all the {@code Cookie} header lines. */
    private static List<String> cookiesNamed(HttpServletRequest request, String name) {
        List<String> values = new ArrayList<>();
        for (String line : Collections.list(request.getHeaders("Cookie"))) {
            for (String pair : line.split(";")) {
                int equals = pair.indexOf('=');
                if (equals > 0 && pair.substring(0, equals).strip().equals(name)) {
                    values.add(pair.substring(equals + 1).strip());
                }
            }
        }
        return values;
    }
}
