package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StaffRole;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Permission;
import java.util.Set;

/**
 * The permissions of the effective role of an account: the one place that reads the stored role code
 * and translates it with the {@link StaffRole} catalog. The permission resolver ({@link
 * AccountPermissions}) and the description of the current session ({@link DescribeCurrentSession}) both
 * use it, each inside its own transaction, so the matrix is interpreted the same way for deciding a
 * request and for telling the interface what the person may do.
 *
 * <p>Failing closed: no row, or a code that is not in the catalog, is no permission at all. A failure
 * of the store propagates.
 */
final class EffectiveRolePermissions {

    private EffectiveRolePermissions() {
    }

    static Set<Permission> of(StaffRoleAssignmentRepository assignments, InstitutionId institution,
            StaffAccountId account) {
        return assignments.findRoleCode(institution, account).flatMap(StaffRole::ofCode)
                .map(StaffRole::permissions).orElse(Set.of());
    }
}
