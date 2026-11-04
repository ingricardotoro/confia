package com.confia.shared.web.authorization;

import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

/**
 * An actor with a full session who does not hold the permission of the route it asked for
 * (rbac-permission-matrix-and-audit-integration design.md D6). It is the one signal of a denial by
 * permission: any other {@code 403} of the chain is a plain {@link AccessDeniedException} and is not
 * told apart. It extends the Spring exception so that the chain answers it with the same {@code 403}
 * as every other denial, and carries what the answer must not repeat: the permission, the template of
 * the entry that matched (never the path of the request) and the actor.
 *
 * <p>The message is fixed, so nothing about the actor or the route reaches a log through it.
 */
public final class PermissionDeniedException extends AccessDeniedException {

    private static final long serialVersionUID = 1L;

    private final Permission permission;
    private final String routeTemplate;
    private final transient AuthenticatedActor actor;

    public PermissionDeniedException(Permission permission, String routeTemplate,
            AuthenticatedActor actor) {
        super("Access is denied");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.routeTemplate = Objects.requireNonNull(routeTemplate, "routeTemplate");
        this.actor = Objects.requireNonNull(actor, "actor");
    }

    /** The permission the route demands. */
    public Permission permission() {
        return permission;
    }

    /** The path pattern of the entry that matched, such as {@code /test/accounts/{id}}. */
    public String routeTemplate() {
        return routeTemplate;
    }

    /** The actor who was refused. */
    public AuthenticatedActor actor() {
        return actor;
    }
}
