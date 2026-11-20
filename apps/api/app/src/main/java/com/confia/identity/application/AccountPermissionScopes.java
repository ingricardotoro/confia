package com.confia.identity.application;

import com.confia.identity.application.directory.PermissionScopes;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StaffRole;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import java.util.Objects;

/**
 * The one adapter of {@link PermissionScopes}: reads the role code stored for the account and asks the catalog whether the role
 * limits the permission to what the account itself created ({@link StaffRole#ownScoped()}). The scope is declared in the role
 * and not in the matrix of permissions (docs/03-seguridad.md section 5.4).
 *
 * <p>It opens no transaction: it runs inside the one of the use case that asks, whose security context is the actor's, so the
 * row-level security keeps an assignment of another institution out. It never calls the permission resolver. Failing closed: no
 * assignment, or a code outside the catalog, answers {@code true} (restricted to its own); a failure of the store propagates and
 * never reads as "not restricted".
 */
public final class AccountPermissionScopes implements PermissionScopes {

    private final StaffRoleAssignmentRepository assignments;

    public AccountPermissionScopes(StaffRoleAssignmentRepository assignments) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
    }

    @Override
    public boolean restrictedToOwn(AuthenticatedActor actor, Permission permission) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(permission, "permission");
        return assignments.findRoleCode(new InstitutionId(actor.institutionId()), new StaffAccountId(actor.accountId()))
                .flatMap(StaffRole::ofCode)
                .map(role -> role.ownScoped().contains(permission))
                .orElse(true);
    }
}
