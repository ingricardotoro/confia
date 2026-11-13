package com.confia.shared.web.authorization;

import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.PermissionResolver;
import com.confia.shared.web.authentication.ActorAuthentication;
import com.confia.shared.web.problem.DataAccessFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Decides, in the filter chain and for one entry of {@link PermissionedEndpoints}, whether the actor
 * of the request holds the permission of that entry (rbac-permission-matrix-and-audit-integration
 * design.md D6, D7 and D12). It is the only class that asks the {@link PermissionResolver}, and it asks
 * once per request, after the bearer filter and before any interceptor or transaction of the business
 * exists.
 *
 * <ul>
 *   <li>Not an {@link ActorAuthentication} (anonymous): denied, and the entry point answers {@code
 *       401}. A restricted second-factor token never gets here: the bearer filter refuses it by its
 *       audience.
 *   <li>A session that did not prove the one-time password: denied without asking the resolver, and
 *       {@code 403 forbidden}. It is an anomaly, since the real policy issues no such token to staff,
 *       and it leaves no audit entry so it opens no flooding path of its own (D12).
 *   <li>The resolver answers a set that holds the permission: granted.
 *   <li>It answers a set that does not: {@link PermissionDeniedException}, with the permission and the
 *       template of the entry, never the path of the request.
 *   <li>It fails: {@link PermissionResolutionFailedException}, which has no cause. Only the classes
 *       and the SQLState are logged, because the message of a driver repeats the values, and the
 *       request ends in {@code 500}, never in a denial and never in a grant.
 * </ul>
 */
public final class PermissionDecider {

    /**
     * The request attribute that holds the permissions the decider resolved for the actor ({@code Set<Permission>}), so a
     * controller that refines the decision (the history of the imports) reads them instead of resolving a second time.
     */
    public static final String RESOLVED_PERMISSIONS = PermissionDecider.class.getName() + ".resolved";

    private static final Logger LOG = LoggerFactory.getLogger(PermissionDecider.class);
    private static final AuthorizationDecision DENIED = new AuthorizationDecision(false);
    private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true);
    private static final String NONE = "none";

    private final PermissionResolver resolver;
    private final AuthorizationMetrics metrics;

    public PermissionDecider(PermissionResolver resolver, AuthorizationMetrics metrics) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** The manager the chain applies to the route of {@code endpoint}. */
    public AuthorizationManager<RequestAuthorizationContext> forEndpoint(
            PermissionedEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        return (authentication, context) -> decide(endpoint, authentication.get(), context.getRequest());
    }

    private AuthorizationResult decide(PermissionedEndpoint endpoint, Authentication authentication,
            HttpServletRequest request) {
        if (!(authentication instanceof ActorAuthentication principal)) {
            return DENIED;
        }
        AuthenticatedActor actor = principal.getPrincipal();
        if (!actor.methods().contains(AuthenticationMethod.ONE_TIME_PASSWORD)) {
            return DENIED;
        }
        Set<Permission> held = resolve(actor);
        request.setAttribute(RESOLVED_PERMISSIONS, Set.copyOf(held));
        if (!held.contains(endpoint.permission())) {
            throw new PermissionDeniedException(endpoint.permission(), endpoint.pattern(), actor);
        }
        for (Permission also : endpoint.alsoRequired()) {
            if (!held.contains(also)) {
                throw new PermissionDeniedException(also, endpoint.pattern(), actor);
            }
        }
        return GRANTED;
    }

    private Set<Permission> resolve(AuthenticatedActor actor) {
        try {
            return resolver.resolve(actor);
        } catch (RuntimeException failure) {
            Optional<DataAccessFailure> data = DataAccessFailure.of(failure);
            LOG.error("Permission resolution failed: exception={} cause={} sqlState={}",
                    failure.getClass().getName(),
                    data.map(DataAccessFailure::causeClass).orElse(NONE),
                    data.map(DataAccessFailure::sqlState).orElse(NONE));
            metrics.resolutionFailed();
            throw new PermissionResolutionFailedException();
        }
    }
}
