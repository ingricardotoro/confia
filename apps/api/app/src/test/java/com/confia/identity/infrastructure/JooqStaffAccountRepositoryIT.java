package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link JooqStaffAccountRepository} against a real PostgreSQL (design.md §10, sonda S4; §11 paso
 * 16). Row-level security on {@code identity_staff_account} (design.md decision 4) is what actually
 * scopes {@link JooqStaffAccountRepository#findBy} to one institution: this class proves the
 * adapter's own query cooperates with that policy rather than fighting it.
 */
class JooqStaffAccountRepositoryIT extends CommittingPostgresIntegrationTest {

    /** Satisfies only the {@code $argon2id$} prefix CHECK — the real codec is exercised elsewhere. */
    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    private JooqStaffAccountRepository repository() {
        return new JooqStaffAccountRepository(dsl);
    }

    @Test
    void findsTheAccountOfItsOwnInstitutionByNormalizedIdentifier() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("Maria.Lopez@Colegio.edu.hn");
        insertStaffAccount(institutionId, accountId, identifier);

        Optional<StaffAccount> found = transactionRunner().execute(contextOf(institutionId),
                () -> repository().findBy(institutionId, identifier));

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(accountId);
        assertThat(found.get().identifier()).isEqualTo(identifier);
        assertThat(found.get().passwordHash())
                .isEqualTo(new StoredPasswordHash(PLACEHOLDER_PASSWORD_HASH));
    }

    @Test
    void findsNothingForAnotherInstitutionsAccount() {
        InstitutionId institutionA = new InstitutionId(UUID.randomUUID());
        InstitutionId institutionB = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("juan.perez@colegio.edu.hn");
        insertStaffAccount(institutionA, new StaffAccountId(UUID.randomUUID()), identifier);

        Optional<StaffAccount> found = transactionRunner().execute(contextOf(institutionB),
                () -> repository().findBy(institutionB, identifier));

        assertThat(found)
                .as("institution B's own session context must never see institution A's row, "
                        + "row-level security first")
                .isEmpty();
    }

    @Test
    void findsNothingForAnUnregisteredIdentifier() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("nadie.registrado@colegio.edu.hn");

        Optional<StaffAccount> found = transactionRunner().execute(contextOf(institutionId),
                () -> repository().findBy(institutionId, identifier));

        assertThat(found).isEmpty();
    }

    @Test
    void lockByIdReturnsTheAccountOfItsOwnInstitution() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("ana.reyes@colegio.edu.hn");
        insertStaffAccount(institutionId, accountId, identifier);

        Optional<StaffAccount> locked = transactionRunner().execute(contextOf(institutionId),
                () -> repository().lockById(institutionId, accountId));

        assertThat(locked).contains(new StaffAccount(accountId, institutionId, identifier,
                new StoredPasswordHash(PLACEHOLDER_PASSWORD_HASH), false));
    }

    @Test
    void lockByIdFindsNothingForAnotherInstitutionsAccount() {
        InstitutionId institutionA = new InstitutionId(UUID.randomUUID());
        InstitutionId institutionB = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        insertStaffAccount(institutionA, accountId, LoginIdentifier.of("luis.mejia@colegio.edu.hn"));

        Optional<StaffAccount> locked = transactionRunner().execute(contextOf(institutionB),
                () -> repository().lockById(institutionB, accountId));

        assertThat(locked).isEmpty();
    }

    /**
     * The serialization password-recovery-token design.md decision 2 rests on: while one
     * transaction holds {@code lockById}, a second {@code lockById} on the same account waits. The
     * waiting is observed with a bounded {@code lock_timeout} in the second transaction, never with a
     * sleep: it fails with {@code 55P03} (lock_not_available) exactly because it had to wait, and
     * succeeds once the first transaction commits.
     */
    @Test
    void aSecondLockByIdOnTheSameAccountWaitsUntilTheFirstTransactionEnds() throws Exception {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        insertStaffAccount(institutionId, accountId, LoginIdentifier.of("sara.flores@colegio.edu.hn"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<StaffAccount>> first = holder.submit(() -> transactionRunner()
                    .execute(contextOf(institutionId), () -> {
                        Optional<StaffAccount> account = repository().lockById(institutionId,
                                accountId);
                        locked.countDown();
                        awaitUninterruptibly(release);
                        return account;
                    }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> transactionRunner().execute(contextOf(institutionId), () -> {
                dsl.execute("set local lock_timeout = '500ms'");
                return repository().lockById(institutionId, accountId);
            }))
                    .as("while the first transaction holds the row, the second lockById must "
                            + "wait for it, which the bounded lock_timeout turns into 55P03")
                    .satisfies(thrown -> assertThat(sqlStateOf(thrown)).isEqualTo("55P03"));

            release.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isPresent();
            assertThat(transactionRunner().execute(contextOf(institutionId), () -> {
                dsl.execute("set local lock_timeout = '500ms'");
                return repository().lockById(institutionId, accountId);
            })).isPresent();
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    @Test
    void replacePasswordHashRewritesTheStoredHashOfTheAccount() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("elena.cruz@colegio.edu.hn");
        insertStaffAccount(institutionId, accountId, identifier);
        StoredPasswordHash newHash = new StoredPasswordHash(
                "$argon2id$v=19$m=19456,t=3,p=1$bnVldmFzYWxzYWx0$bnVldm9oYXNobnVldm9oYXNo");

        boolean replaced = transactionRunner().execute(contextOf(institutionId),
                () -> repository().replacePasswordHash(institutionId, accountId, newHash));

        assertThat(replaced).isTrue();
        assertThat(transactionRunner().execute(contextOf(institutionId),
                () -> repository().findBy(institutionId, identifier)))
                .get().extracting(StaffAccount::passwordHash).isEqualTo(newHash);
    }

    @Test
    void replacePasswordHashReportsFalseForAnAccountThatDoesNotExist() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());

        boolean replaced = transactionRunner().execute(contextOf(institutionId),
                () -> repository().replacePasswordHash(institutionId,
                        new StaffAccountId(UUID.randomUUID()),
                        new StoredPasswordHash(PLACEHOLDER_PASSWORD_HASH)));

        assertThat(replaced).isFalse();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The first {@link SQLException} SQLState found along the cause chain. */
    private static String sqlStateOf(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private void insertStaffAccount(InstitutionId institutionId, StaffAccountId accountId,
            LoginIdentifier identifier) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, false)
                    """, institutionId.value(), accountId.value(), identifier.value(),
                    PLACEHOLDER_PASSWORD_HASH);
            return null;
        });
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
