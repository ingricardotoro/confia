package com.confia.identity.application;

import java.util.List;
import java.util.Objects;

/**
 * What one role grants, as the roles report shows it (rbac-permission-matrix-and-audit-integration
 * design.md D16): the stable role code and the permission codes it holds, sorted by code. It carries
 * no display name (the interface translates the code with its own catalog), no account and no
 * institution. The {@code web} layer copies it into its own explicit DTO.
 *
 * @param code the stable role code, such as {@code cashier}
 * @param permissions the {@code module:verb} codes the role grants, sorted; never modifiable
 */
public record RolePermissionsView(String code, List<String> permissions) {

    public RolePermissionsView {
        Objects.requireNonNull(code, "code");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }
}
