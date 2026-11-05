package com.confia.shared.web.authorization;

import com.confia.kernel.Permission;
import java.util.Objects;
import org.springframework.http.HttpMethod;

/**
 * One route of the administrative chain that needs a permission: an HTTP method, a path pattern in
 * the path pattern syntax of Spring and the permission of the catalog it demands
 * (rbac-permission-matrix-and-audit-integration design.md D6 and D13). A route is on the list only for
 * the method it names, so another method on the same path is denied like any other request.
 *
 * <p>The permission is a {@link Permission}, not a text: an entry that names something outside the
 * catalog cannot be compiled, so no list with such an entry can exist at run time (scenario AZ30).
 *
 * @param method the one HTTP method the entry covers
 * @param pattern the path pattern; it is also the template that an audit entry of a denial records
 * @param permission the permission the actor must hold
 */
public record PermissionedEndpoint(HttpMethod method, String pattern, Permission permission) {

    public PermissionedEndpoint {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(permission, "permission");
    }
}
