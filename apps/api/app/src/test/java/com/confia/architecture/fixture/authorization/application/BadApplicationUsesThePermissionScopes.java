package com.confia.architecture.fixture.authorization.application;

import com.confia.identity.application.directory.PermissionScopes;
import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;

/**
 * A permanent, deliberately invalid fixture: a class of an {@code application} package outside {@code identity} that asks the
 * role directory how far a permission reaches. Only an adapter of {@code cashbox} may; a use case learns the scope through a
 * port of its own.
 */
public final class BadApplicationUsesThePermissionScopes {

    private final PermissionScopes scopes;

    public BadApplicationUsesThePermissionScopes(PermissionScopes scopes) {
        this.scopes = scopes;
    }

    public boolean restricted(AuthenticatedActor actor) {
        return scopes.restrictedToOwn(actor, Permission.CASHBOX_READ);
    }
}
