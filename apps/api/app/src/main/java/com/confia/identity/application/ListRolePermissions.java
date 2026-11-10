package com.confia.identity.application;

import com.confia.identity.domain.StaffRole;
import com.confia.kernel.Permission;
import java.util.Arrays;
import java.util.List;

/**
 * The roles report: which role can do what (rbac-permission-matrix-and-audit-integration design.md
 * D16; ADR-0027). It answers the constant matrix of {@link StaffRole}, the roles in the order of the
 * catalog and the permissions of each sorted by code, so two calls give the same answer.
 *
 * <p>It opens no transaction, reads no table and takes no actor: the matrix is code, not data, and
 * who may ask for it is decided before this runs, by the permission decider of the administrative
 * chain over the route's {@code identity:read}.
 */
public final class ListRolePermissions {

    public List<RolePermissionsView> execute() {
        return Arrays.stream(StaffRole.values())
                .map(role -> new RolePermissionsView(role.code(),
                        role.permissions().stream().map(Permission::code).sorted().toList()))
                .toList();
    }
}
