package com.confia.shared.web.authentication;

import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.PendingSecondFactor;
import com.confia.shared.security.SecondFactorPurpose;
import com.confia.shared.security.SessionValidity;
import com.confia.shared.security.token.AccessTokenVerifier;
import com.confia.shared.security.token.MfaPurpose;
import com.confia.shared.security.token.MfaTokenClaims;
import com.confia.shared.security.token.TokenRejectedException;
import com.confia.shared.security.token.TokenRejection;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the requests of the administrative chain from {@code Authorization: Bearer} and
 * from nowhere else (session-tokens-and-web-layer design.md, decision 5). It is the only class of the
 * web layer that reads a token: everything after it reads the {@link AuthenticatedActor} it binds.
 *
 * <ol>
 *   <li>No {@code Authorization} header at all, or a scheme other than {@code Bearer}: the request goes on
 *       as anonymous, and the chain decides.
 *   <li>More than one {@code Authorization} header, one that is empty or only whitespace, a {@code
 *       Bearer} header that is not exactly one space and a token, or a token the verifier refuses: the chain is not continued. The entry
 *       point answers {@code token-expired} for an intact token whose time has passed and {@code
 *       token-invalid} for everything else, on a public route as much as on a protected one, because
 *       a client that sent a broken credential should hear the cause.
 *   <li>A valid token on a route that declares a live-session check, whose session the {@link
 *       SessionValidity} port says is no longer active: the same {@code token-invalid} answer.
 *   <li>A valid token: Spring's context holds an {@link ActorAuthentication} for the authorization
 *       rule, and the rest of the chain runs with {@link AuthenticatedActor#CURRENT} bound, exactly
 *       as the request filter binds the request origin.
 *   <li>A request for a route of the restricted class ({@link AuthenticatedEndpoints#secondFactorEndpoints()}):
 *       the token is verified as a restricted second-factor token and as nothing else, so a token of
 *       access fails by its audience, and a purpose the route does not admit is the same {@code
 *       token-invalid}. Success puts a {@link SecondFactorAuthentication} in Spring's context and
 *       binds {@link PendingSecondFactor#CURRENT}, never {@link AuthenticatedActor#CURRENT}, so
 *       nothing that reads the session actor ever sees a restricted token. On every other route a
 *       restricted token still fails by its audience in {@code verifyAccess}, unchanged.
 * </ol>
 *
 * <p>A credential in the query string, a cookie, the body or another header is never looked at. The
 * filter creates no session and no cookie, keeps nothing between requests and writes nothing to any
 * log: not the header, not the token, not a claim. It is not a bean; {@code
 * AdminSecurityConfiguration} builds it with {@code new}, so the portal cannot acquire it and the
 * servlet container does not register it a second time.
 */
public final class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer";
    private static final char SPACE = ' ';
    private static final char TAB = '\t';
    private static final Pattern TOKEN68 = Pattern.compile("[A-Za-z0-9._~+/-]+=*");

    private final AccessTokenVerifier verifier;
    private final SessionValidity sessionValidity;
    private final List<RequestMatcher> liveSessionRoutes;
    private final List<RestrictedRoute> restrictedRoutes;
    private final AuthenticationEntryPoint entryPoint;

    public AccessTokenAuthenticationFilter(AccessTokenVerifier verifier,
            AuthenticatedEndpoints endpoints, SessionValidity sessionValidity,
            AuthenticationEntryPoint entryPoint) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.sessionValidity = Objects.requireNonNull(sessionValidity, "sessionValidity");
        this.entryPoint = Objects.requireNonNull(entryPoint, "entryPoint");
        PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();
        this.liveSessionRoutes = endpoints.endpoints().stream()
                .filter(endpoint -> endpoint.check() == SessionCheck.LIVE_SESSION)
                .<RequestMatcher>map(endpoint -> matchers.matcher(endpoint.method(),
                        endpoint.pattern()))
                .toList();
        this.restrictedRoutes = endpoints.secondFactorEndpoints().stream()
                .map(endpoint -> new RestrictedRoute(
                        matchers.matcher(endpoint.method(), endpoint.pattern()),
                        endpoint.purposes()))
                .toList();
    }

    /** A route of the restricted class with the purposes of restricted token it admits. */
    private record RestrictedRoute(RequestMatcher matcher, Set<SecondFactorPurpose> purposes) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            AuthenticatedActor actor = null;
            PendingSecondFactor pending = null;
            try {
                Optional<String> token = bearerTokenOf(request);
                if (token.isPresent()) {
                    Optional<RestrictedRoute> restricted = restrictedRouteOf(request);
                    if (restricted.isPresent()) {
                        pending = authenticateRestricted(token.get(), restricted.get());
                    } else {
                        actor = authenticate(token.get(), request);
                    }
                }
            } catch (AccessTokenRejectedException rejection) {
                entryPoint.commence(request, response, rejection);
                return;
            }
            if (actor == null && pending == null) {
                chain.doFilter(request, response);
                return;
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            if (pending != null) {
                context.setAuthentication(new SecondFactorAuthentication(pending));
                SecurityContextHolder.setContext(context);
                callWith(PendingSecondFactor.CURRENT, pending, request, response, chain);
            } else {
                context.setAuthentication(new ActorAuthentication(actor));
                SecurityContextHolder.setContext(context);
                callWith(AuthenticatedActor.CURRENT, actor, request, response, chain);
            }
        } finally {
            // The filter owns what it put on the thread: a pooled worker never starts the next
            // request with this caller's principal, whatever else clears the context.
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * The token of a well-formed {@code Bearer} header; empty when there is no header or its scheme
     * is another one.
     *
     * @throws AccessTokenRejectedException for two headers, or a {@code Bearer} header of another
     *     shape than one space and a token
     */
    private static Optional<String> bearerTokenOf(HttpServletRequest request) {
        List<String> headers = Collections.list(request.getHeaders(AUTHORIZATION));
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        if (headers.size() > 1) {
            throw new AccessTokenRejectedException();
        }
        String value = headers.get(0);
        if (value.isBlank()) {
            throw new AccessTokenRejectedException();
        }
        int end = schemeEnd(value);
        if (!BEARER.equalsIgnoreCase(value.substring(0, end))) {
            return Optional.empty();
        }
        String rest = value.substring(end);
        if (rest.length() < 2 || rest.charAt(0) != SPACE
                || !TOKEN68.matcher(rest.substring(1)).matches()) {
            throw new AccessTokenRejectedException();
        }
        return Optional.of(rest.substring(1));
    }

    private static int schemeEnd(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == SPACE || c == TAB) {
                return i;
            }
        }
        return value.length();
    }

    /**
     * Verifies the token and, for a route whose entry declares a live-session check, asks the port
     * whether the session behind it is still valid (decision 7). An inactive session is a credential
     * that no longer holds: {@code token-invalid}, never {@code 403}. A route without the check never
     * reaches the port, so it accepts the token until it expires. A failure of the port is not caught
     * here: it propagates and the request ends in {@code 500}, which fails closed.
     */
    private AuthenticatedActor authenticate(String token, HttpServletRequest request) {
        AuthenticatedActor actor;
        try {
            actor = verifier.verifyAccess(token);
        } catch (TokenRejectedException e) {
            throw e.rejection() == TokenRejection.EXPIRED ? new AccessTokenExpiredException()
                    : new AccessTokenRejectedException();
        }
        if (liveSessionRoutes.stream().anyMatch(route -> route.matches(request))
                && !sessionValidity.isActive(actor)) {
            throw new AccessTokenRejectedException();
        }
        return actor;
    }

    /** The route of the restricted class this request is for, if it is for one. */
    private Optional<RestrictedRoute> restrictedRouteOf(HttpServletRequest request) {
        return restrictedRoutes.stream().filter(route -> route.matcher().matches(request))
                .findFirst();
    }

    /**
     * Verifies the token as a restricted token and nothing else: a token of access fails by its
     * audience, and a purpose the route does not admit fails like any other broken credential. The
     * state of the credential behind the account is not consulted here; what a purpose may prove is
     * the use case's decision. The session-validity port is never asked, because a restricted token
     * is not a session.
     */
    private PendingSecondFactor authenticateRestricted(String token, RestrictedRoute route) {
        MfaTokenClaims claims;
        try {
            claims = verifier.verifyMfa(token);
        } catch (TokenRejectedException e) {
            throw e.rejection() == TokenRejection.EXPIRED ? new AccessTokenExpiredException()
                    : new AccessTokenRejectedException();
        }
        SecondFactorPurpose purpose = purposeOf(claims.purpose());
        if (!route.purposes().contains(purpose)) {
            throw new AccessTokenRejectedException();
        }
        return new PendingSecondFactor(claims.accountId(), claims.institutionId(),
                claims.tokenId(), purpose, claims.issuedAt(), claims.expiresAt());
    }

    /** The one place the claim of the token becomes the purpose the web layer knows. */
    private static SecondFactorPurpose purposeOf(MfaPurpose claim) {
        return switch (claim) {
            case MFA_VERIFY -> SecondFactorPurpose.VERIFY;
            case MFA_ENROLL -> SecondFactorPurpose.ENROLL;
        };
    }

    /**
     * Runs the rest of the chain with {@code principal} bound to {@code scoped}, and only for the
     * length of that call. Checked exceptions of the chain come out as they went in.
     */
    private static <T> void callWith(ScopedValue<T> scoped, T principal, HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        try {
            ScopedValue.where(scoped, principal).call(() -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // The call is declared to throw Exception; the chain throws nothing but the three above.
            throw new IllegalStateException(e);
        }
    }
}
