package com.confia.shared.web.authorization.access;

import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.web.authorization.PermissionDeniedException;
import org.springframework.security.access.AccessDeniedException;

/** The refusals a controller may throw to end a request as the decider would have. */
public final class AccessRefusals {

    private AccessRefusals() {
    }

    /**
     * The refusal for an actor that lacks {@code permission} on the route {@code routeTemplate}: the filter chain
     * answers it with {@code 403 forbidden} and records the denial with the permission and the template, never the
     * path of the request.
     */
    public static AccessDeniedException missing(Permission permission, String routeTemplate,
            AuthenticatedActor actor) {
        return new PermissionDeniedException(permission, routeTemplate, actor);
    }
}
