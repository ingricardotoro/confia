package com.confia.identity.application.directory;

import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;

/**
 * Output port, read-only, through which a module asks {@code identity} how far a permission of the actor reaches: whether the
 * role of the account limits it to what the account itself created (docs/03-seguridad.md section 5.4). It answers a question
 * about the role and never about the permission itself: holding the permission is decided before a controller runs.
 *
 * <p>It runs inside the transaction of the caller, opens none and takes no lock. It fails closed: an account without a role
 * assignment, or with a role code outside the catalog, is restricted to its own.
 */
public interface PermissionScopes {

    /** Whether {@code permission} reaches only what the actor itself created; {@code true} when the role is unknown. */
    boolean restrictedToOwn(AuthenticatedActor actor, Permission permission);
}
