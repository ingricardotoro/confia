package com.confia.identity.web;

import com.confia.identity.application.ListRolePermissions;
import com.confia.identity.application.RolePermissionsView;
import com.confia.shared.web.openapi.ContractSchemas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/identity/roles}: which role can do what (rbac-permission-matrix-and-audit-integration
 * design.md D16; ADR-0027), the first route that needs a permission.
 *
 * <p>The controller reads nothing from the request. Whether the actor holds {@code identity:read} is
 * decided before it runs, by the permission decider of the administrative chain over the entry of
 * {@code PermissionedEndpoints}, from the role the server stored for the account; a denied request never
 * gets here and leaves its audit entry there. A granted one leaves none. The answer is a constant of
 * the catalog, so there is no {@code Idempotency-Key} and no rate limiter; {@code Cache-Control:
 * no-store} is set on every response by the security headers filter.
 *
 * <p>The approved answers are declared one by one with {@code @ApiResponse}, so the contract snapshot
 * holds exactly these four and not whatever the global translator could answer. The permission the
 * route needs travels in the {@code x-required-permission} extension of the operation, which is what
 * the client contracts and the snapshot read.
 */
@RestController
@RequestMapping("/api/v1/identity")
public class RolesReportController {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ListRolePermissions list;

    public RolesReportController(ListRolePermissions list) {
        this.list = list;
    }

    @GetMapping(path = "/roles", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listRolePermissions", tags = "identity-roles",
            summary = "Reports which role can do what",
            description = "Returns the roles of the catalog and the permissions each one grants, as "
                    + "codes. Needs an access token of a live session whose account holds the "
                    + "permission identity:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "identity:read")))
    @ApiResponse(responseCode = "200", description = "The roles and their permissions",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = RolesReportResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold identity:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public RolesReportResponse roles() {
        List<RolesReportResponse.Role> roles = list.execute().stream()
                .map(RolesReportController::toDto).toList();
        return new RolesReportResponse(roles);
    }

    private static RolesReportResponse.Role toDto(RolePermissionsView view) {
        return new RolesReportResponse.Role(view.code(), view.permissions());
    }
}
