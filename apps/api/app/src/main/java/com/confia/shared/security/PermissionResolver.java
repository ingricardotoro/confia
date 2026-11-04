package com.confia.shared.security;

import com.confia.kernel.Permission;
import java.util.Set;

/**
 * The permissions of the account behind a request, declared here so that {@code shared} never depends
 * on {@code identity} (rbac-permission-matrix-and-audit-integration design.md D5; ADR-0027).
 * {@code identity} implements it with {@code AccountPermissions}; the permission decider of the
 * administrative chain is the only class that asks it.
 *
 * <p>The answer is read for every request and never cached, so a change of role takes effect on the
 * next one. The token and the {@link AuthenticatedActor} carry no permissions.
 */
public interface PermissionResolver {

    /**
     * The effective permissions of the account of {@code actor} in the institution of its token; empty
     * when it has no role or an unknown one. A failure of the store is thrown, never answered: an
     * error must not read as "no permissions", and much less as "allowed".
     */
    Set<Permission> resolve(AuthenticatedActor actor);
}
