package com.confia.cashbox.infrastructure;

import com.confia.cashbox.application.SessionScope;
import com.confia.identity.application.directory.PermissionScopes;
import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import java.util.Objects;

/**
 * The scope of the reads, answered by the role directory that {@code identity} publishes ({@link PermissionScopes}): an actor
 * whose role limits {@code cashbox:read} to its own data (the cashier, docs/03-seguridad.md section 5.4) reads only its own
 * sessions; every other role reads all of them. It runs inside the transaction of the use case and fails closed with the
 * directory: an unknown role is restricted.
 */
public final class RoleDirectorySessionScope implements SessionScope {

    private final PermissionScopes scopes;

    public RoleDirectorySessionScope(PermissionScopes scopes) {
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    @Override
    public boolean readsEverySession(AuthenticatedActor actor) {
        return !scopes.restrictedToOwn(actor, Permission.CASHBOX_READ);
    }
}
