package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityStaffAccountRole.IDENTITY_STAFF_ACCOUNT_ROLE;

import com.confia.identity.application.StaffRoleAssignmentRepository;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link StaffRoleAssignmentRepository} (rbac-permission-matrix-and-audit-integration
 * design.md D4; ADR-0015 rule 4: jOOQ confined to {@code infrastructure}). {@code final}, with an explicit
 * constructor over {@link DSLContext} and no Spring annotation. It only reads, because the administrative
 * role holds {@code SELECT} alone on the table, and it never opens a transaction of its own (ADR-0015
 * rule 7): it runs inside the read-only one of the resolver.
 */
public final class JooqStaffRoleAssignmentRepository implements StaffRoleAssignmentRepository {

    private final DSLContext dsl;

    public JooqStaffRoleAssignmentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<String> findRoleCode(InstitutionId institutionId, StaffAccountId accountId) {
        return dsl.select(IDENTITY_STAFF_ACCOUNT_ROLE.ROLE_CODE)
                .from(IDENTITY_STAFF_ACCOUNT_ROLE)
                .where(IDENTITY_STAFF_ACCOUNT_ROLE.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_STAFF_ACCOUNT_ROLE.ACCOUNT_ID.eq(accountId.value()))
                .fetchOptional(IDENTITY_STAFF_ACCOUNT_ROLE.ROLE_CODE);
    }
}
