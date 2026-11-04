package com.confia.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.AccountPermissions;
import com.confia.identity.application.StaffRoleAssignmentRepository;
import com.confia.identity.domain.StaffRole;
import com.confia.identity.infrastructure.JooqStaffRoleAssignmentRepository;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.StaffRoleFixtures;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The permission resolver against a real PostgreSQL, with the real adapter and the real row-level
 * security of {@code confia_admin_app} (rbac-permission-matrix-and-audit-integration design.md D4
 * and D5; scenarios AZ20, AZ23, I285 and I286). Assignments are seeded as {@code confia_owner}
 * through {@link StaffRoleFixtures}, because the application role cannot write the table. A code
 * outside the catalog (AZ19) needs the constraint dropped, which no shared database may see, so it
 * lives in {@code IdentityStaffAccountRoleSchemaIT}, on a database of its own.
 */
class AccountPermissionsIT extends CommittingPostgresIntegrationTest {

    private static final Instant NOON = Instant.parse("2026-11-04T12:00:00Z");

    private AccountPermissions resolver() {
        return new AccountPermissions(transactionRunner(),
                new JooqStaffRoleAssignmentRepository(dsl));
    }

    private static AuthenticatedActor actor(UUID institution, UUID account) {
        return new AuthenticatedActor(account, institution, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD), NOON,
                NOON.plusSeconds(600));
    }

    @Test
    void aSeededRoleResolvesToItsColumnAndAnAccountWithoutRowToNothing() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID withRole = UUID.randomUUID();
        UUID withoutRole = UUID.randomUUID();
        StaffRoleFixtures.committed(institution, withRole, "accounting");
        StaffRoleFixtures.committed(institution, withoutRole, null);

        assertThat(resolver().resolve(actor(institution, withRole)))
                .isEqualTo(StaffRole.ACCOUNTING.permissions());
        assertThat(resolver().resolve(actor(institution, withoutRole))).isEmpty();
    }

    @Test
    void theAssignmentOfTheSameAccountIdInAnotherInstitutionIsNeverUsed() throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(first, account, null);
        StaffRoleFixtures.committed(second, account, "super_admin");

        assertThat(resolver().resolve(actor(first, account))).as("token of INS-1").isEmpty();
        assertThat(resolver().resolve(actor(second, account)))
                .as("non-vacuous: under INS-2 the same account id is a Super Admin").hasSize(88);
    }

    @Test
    void theInstitutionComesFromTheActorAndAForeignHeaderOfTheRequestDoesNotEnter()
            throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(first, account, "auditor");
        StaffRoleFixtures.committed(second, account, "super_admin");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/identity/roles");
        request.addHeader("X-Institution-Id", second.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThat(resolver().resolve(actor(first, account)))
                    .isEqualTo(StaffRole.AUDITOR.permissions());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void theReadRunsInAReadOnlyTransactionThatIsClosedWhenResolveReturns() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(institution, account, "cashier");
        List<Boolean> insideActive = new ArrayList<>();
        List<Boolean> insideReadOnly = new ArrayList<>();
        StaffRoleAssignmentRepository spy = (institutionId, accountId) -> {
            insideActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            insideReadOnly.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return new JooqStaffRoleAssignmentRepository(dsl).findRoleCode(institutionId, accountId);
        };

        new AccountPermissions(transactionRunner(), spy).resolve(actor(institution, account));

        assertThat(insideActive).containsExactly(true);
        assertThat(insideReadOnly).containsExactly(true);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("closed before the caller, the controller, runs").isFalse();
    }

    @Test
    void aWriteInTheTransactionOfTheResolutionIsRefusedByTheEngine() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(institution, account, null);
        StaffRoleAssignmentRepository writing = (institutionId, accountId) -> {
            dsl.execute("""
                    insert into identity_staff_account_role (institution_id, account_id, role_code)
                    values (?, ?, 'super_admin')
                    """, institutionId.value(), accountId.value());
            return Optional.of("super_admin");
        };

        assertThatThrownBy(() -> new AccountPermissions(transactionRunner(), writing)
                .resolve(actor(institution, account)))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().satisfies(cause -> assertThat(((SQLException) cause).getSQLState())
                        .as("read_only_sql_transaction").isEqualTo("25006"));
        assertThat(resolver().resolve(actor(institution, account)))
                .as("nothing was assigned").isEmpty();
    }
}
