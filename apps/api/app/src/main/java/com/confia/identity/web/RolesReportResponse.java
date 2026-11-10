package com.confia.identity.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * The body of {@code GET /api/v1/identity/roles} (rbac-permission-matrix-and-audit-integration design.md
 * D16): the roles of the catalog and the permissions each one grants, as codes. It is the explicit output
 * DTO of the route (CLAUDE.md, rule 10): plain text only, no display name (the interface translates the
 * role code with its own catalog), no account, no institution and no type of {@code domain} or of jOOQ.
 *
 * @param roles the roles in the order of the catalog
 */
public record RolesReportResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "The roles of the catalog, in catalog order")
        List<Role> roles) {

    /**
     * One role of the report.
     *
     * @param code the stable role code, such as {@code cashier}
     * @param permissions the {@code module:verb} codes the role grants, sorted by code
     */
    @Schema(name = "RolePermissions")
    public record Role(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Stable role code")
            String code,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    description = "Permission codes (module:verb) the role grants, sorted")
            List<String> permissions) {
    }
}
