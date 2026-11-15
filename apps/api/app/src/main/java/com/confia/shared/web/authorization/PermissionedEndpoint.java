package com.confia.shared.web.authorization;

import com.confia.kernel.Permission;
import java.util.List;
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
 * <p>A route that changes two things at once may demand a second permission as well ({@code alsoRequired}): the actor
 * must hold <b>all</b> of them, and the first one it lacks is the one a denial records. The import of guardian links
 * creates guardians' links and is therefore {@code guardians:create} and, in addition, {@code guardians:update}
 * (docs/cortes/imports-guardians.md).
 *
 * @param method the one HTTP method the entry covers
 * @param pattern the path pattern; it is also the template that an audit entry of a denial records
 * @param permission the permission the actor must hold
 * @param alsoRequired the other permissions the actor must hold, if any
 */
public record PermissionedEndpoint(HttpMethod method, String pattern, Permission permission,
        List<Permission> alsoRequired) {

    public PermissionedEndpoint {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(permission, "permission");
        alsoRequired = List.copyOf(Objects.requireNonNull(alsoRequired, "alsoRequired"));
    }

    /** A route that needs one permission. */
    public PermissionedEndpoint(HttpMethod method, String pattern, Permission permission) {
        this(method, pattern, permission, List.of());
    }
}
