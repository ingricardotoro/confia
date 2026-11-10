package com.confia.shared.web.authorization;

import com.confia.kernel.Permission;
import java.util.List;
import org.springframework.http.HttpMethod;

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
     * The list of the administrative process: the one route that reports which role can do what,
     * which needs {@code identity:read} (design.md D16). A route of a later change is added here, in
     * the same commit as its controller.
     */
    public static PermissionedEndpoints forAdmin() {
        return new PermissionedEndpoints(List.of(
                new PermissionedEndpoint(HttpMethod.GET, "/api/v1/identity/roles",
                        Permission.IDENTITY_READ)));
    }

    /** The routes, in a list that cannot be modified. */
    public List<PermissionedEndpoint> endpoints() {
        return endpoints;
    }
}
