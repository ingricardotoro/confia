package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.util.Optional;

/**
 * Where the role assignment of a staff account is read (rbac-permission-matrix-and-audit-integration
 * design.md D4 and D5). It never opens a transaction: it runs inside the one the use case opened.
 * {@link com.confia.identity.infrastructure.JooqStaffRoleAssignmentRepository} is its adapter. Nothing
 * writes an assignment in production yet; change 8b adds the operation that does.
 */
public interface StaffRoleAssignmentRepository {

    /**
     * The role code stored for the account in the institution, as text and not as a {@code StaffRole},
     * so that deciding what an unknown code means happens in one place and a code outside the catalog
     * never throws here. Empty when the account has no assignment.
     */
    Optional<String> findRoleCode(InstitutionId institutionId, StaffAccountId accountId);
}
