package com.confia.shared.web.authorization;

import java.util.List;

/**
 * The closed, immutable list of routes of the administrative chain that need a permission, parallel to
 * {@code PublicEndpoints} and {@code AuthenticatedEndpoints} and defined in this one place
 * (rbac-permission-matrix-and-audit-integration design.md D6). A route on no list is denied, so adding a
 * controller never opens a route by itself.
 *
 * <p>There is one instance per process: the security chain decides over it, and the bearer filter
 * derives from the same instance the routes whose session must be alive, so there is no second list
 * that can drift from the first. The public constructor exists for the test harnesses, which add
 * routes of their own and nothing else.
 */
public final class PermissionedEndpoints {

    private final List<PermissionedEndpoint> endpoints;

    public PermissionedEndpoints(List<PermissionedEndpoint> endpoints) {
        this.endpoints = List.copyOf(endpoints);
    }

    /**
     * The list of the administrative process. It is empty until the endpoint that reports the roles
     * arrives, which is the first route that needs a permission (tasks 6 and 7 of the change).
     */
    public static PermissionedEndpoints forAdmin() {
        return new PermissionedEndpoints(List.of());
    }

    /** The routes, in a list that cannot be modified. */
    public List<PermissionedEndpoint> endpoints() {
        return endpoints;
    }
}
