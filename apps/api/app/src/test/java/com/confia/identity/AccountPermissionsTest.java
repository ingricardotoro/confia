package com.confia.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.AccountPermissions;
import com.confia.identity.application.StaffRoleAssignmentRepository;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StaffRole;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.support.JdbcStubTransactions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link AccountPermissions} with the assignment repository replaced by a double
 * (rbac-permission-matrix-and-audit-integration design.md D5; scenarios AZ17 to AZ19, AZ21, I285 and
 * I286): which role code becomes which set, that nothing ever becomes more than the stored role, that
 * a failure of the read is thrown and never answered as an empty set, and which institution and
 * account the read is made for. The real rows and the real row-level security are
 * {@code AccountPermissionsIT}.
 */
class AccountPermissionsTest {

    private static final Instant NOON = Instant.parse("2026-11-04T12:00:00Z");
    private static final UUID INSTITUTION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    private final List<String> transaction = new ArrayList<>();
    private final List<String> readOnlyFlags = new ArrayList<>();
    private final FakeAssignments assignments = new FakeAssignments();
    private final AccountPermissions permissions = new AccountPermissions(
            JdbcStubTransactions.runner(transaction, readOnlyFlags), assignments);

    private static AuthenticatedActor actor() {
        return new AuthenticatedActor(ACCOUNT, INSTITUTION, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD), NOON,
                NOON.plusSeconds(600));
    }

    @Test
    void aStoredRoleResolvesToExactlyThePermissionsOfItsColumn() {
        assignments.stored = Optional.of("accounting");
        assertThat(permissions.resolve(actor())).as("accounting").hasSize(31)
                .isEqualTo(StaffRole.ACCOUNTING.permissions())
                .doesNotContain(Permission.PAYMENTS_CREATE, Permission.AUDIT_READ);

        assignments.stored = Optional.of("cashier");
        assertThat(permissions.resolve(actor())).as("another role, another set").hasSize(18)
                .isEqualTo(StaffRole.CASHIER.permissions())
                .contains(Permission.PAYMENTS_CREATE).doesNotContain(Permission.LEDGER_VOID);
    }

    @Test
    void noRowIsASuccessfulResolutionOfNoPermissionAtAll() {
        assignments.stored = Optional.empty();

        assertThat(permissions.resolve(actor())).isEmpty();
        assertThat(transaction).as("it answered, so the transaction committed")
                .containsExactly("commit");
    }

    @Test
    void anUnknownEmptyOrDifferentlySpelledCodeGrantsNothing() {
        for (String code : List.of("ghost_role", "", " cashier", "CASHIER", "super_admin ")) {
            assignments.stored = Optional.of(code);
            assertThat(permissions.resolve(actor())).as("code [%s]", code).isEmpty();
        }
        assignments.stored = Optional.of("super_admin");
        assertThat(permissions.resolve(actor())).as("non-vacuous: the exact code does grant")
                .hasSize(88);
    }

    @Test
    void aFailureOfTheReadIsThrownNeverAnsweredAsAnEmptySetOrAsMore() {
        IllegalStateException failure = new IllegalStateException("the database failed");
        assignments.failure = failure;

        assertThatThrownBy(() -> permissions.resolve(actor())).isSameAs(failure);
        assertThat(transaction).containsExactly("rollback");
    }

    @Test
    void theReadIsForTheInstitutionAndTheAccountOfTheActorInAReadOnlyTransaction() {
        assignments.stored = Optional.of("auditor");

        permissions.resolve(actor());

        assertThat(assignments.institutions).containsExactly(new InstitutionId(INSTITUTION));
        assertThat(assignments.accounts).containsExactly(new StaffAccountId(ACCOUNT));
        assertThat(readOnlyFlags).as("the engine refuses a write in it").contains("true");
    }

    @Test
    void anAbsentActorIsAProgrammingError() {
        assertThatThrownBy(() -> permissions.resolve(null)).isInstanceOf(NullPointerException.class);
    }

    private static final class FakeAssignments implements StaffRoleAssignmentRepository {

        final List<InstitutionId> institutions = new ArrayList<>();
        final List<StaffAccountId> accounts = new ArrayList<>();
        Optional<String> stored = Optional.empty();
        RuntimeException failure;

        @Override
        public Optional<String> findRoleCode(InstitutionId institutionId,
                StaffAccountId accountId) {
            institutions.add(institutionId);
            accounts.add(accountId);
            if (failure != null) {
                throw failure;
            }
            return stored;
        }
    }
}
