package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.AuthenticationResult;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PasswordResetTokenRow;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Concurrency of password-reset issuance and reset (password-recovery-token design.md decisions 2
 * and 7, sondas S1 and S3; specs/identity/spec.md, "Dos emisiones concurrentes dejan exactamente un
 * token vivo" and "Dos restablecimientos concurrentes con el mismo token producen un solo cambio").
 *
 * <p>The account row lock is what keeps "at most one open token per account"; the partial unique
 * index is only a safety net. The negative control proves both halves of that sentence: with a
 * lock that does not lock, two issuances that both counted and superseded before either inserted
 * end with the {@code 23505} sonda S1 observed, never with two open tokens.
 */
class PasswordResetConcurrencyIT extends PasswordResetIntegrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:30Z");

    private final List<PlainPasswordResetToken> sent =
            Collections.synchronizedList(new ArrayList<>());

    /** Scenario I7: both issue, one after the other, and exactly one token stays open. */
    @Test
    void twoConcurrentIssuancesLeaveExactlyOneOpenTokenAndTheOtherSuperseded() throws Exception {
        StaffAccountId account = seedStaffAccount();
        CyclicBarrier start = new CyclicBarrier(2);
        IssuePasswordResetToken useCase = useCase(new JooqStaffAccountRepository(dsl),
                new JooqPasswordResetTokenRepository(dsl));

        List<IssuePasswordResetTokenDecision> decisions = runTwice(() -> {
            start.await(30, TimeUnit.SECONDS);
            return useCase.execute(context(), account);
        });

        assertThat(decisions).containsExactly(IssuePasswordResetTokenDecision.ISSUED,
                IssuePasswordResetTokenDecision.ISSUED);
        assertThat(sent).hasSize(2);
        List<PasswordResetTokenRow> rows = sent.stream().map(this::rowOf).map(Optional::orElseThrow)
                .toList();
        assertThat(rows).filteredOn(row -> row.consumedAt() == null && row.supersededAt() == null)
                .as("the account lock serializes the two issuances, so the second supersedes the "
                        + "first and exactly one token stays open")
                .hasSize(1);
        assertThat(rows).filteredOn(row -> row.supersededAt() != null).hasSize(1);
    }

    /**
     * The negative control: a {@code lockById} that does not lock, and a barrier that holds both
     * issuances until each has counted and superseded, so both insert an open token for the same
     * account. The first insert wins; the second waits on it and fails with {@code 23505} once it
     * commits (sonda S1). {@code TransactionRunner} does not retry it, and exactly one token is open.
     */
    @Test
    void withoutTheAccountLockThePartialUniqueIndexRejectsTheSecondOpenToken() throws Exception {
        StaffAccountId account = seedStaffAccount();
        CyclicBarrier beforeInsert = new CyclicBarrier(2);
        StaffAccountRepository nonLocking = new NonLockingAccounts(institutionId, account);
        PasswordResetTokenRepository insertTogether = new BarrierBeforeInsert(
                new JooqPasswordResetTokenRepository(dsl), beforeInsert);
        IssuePasswordResetToken useCase = useCase(nonLocking, insertTogether);

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<IssuePasswordResetTokenDecision> decisions = runTwice(() -> {
            try {
                return useCase.execute(context(), account);
            } catch (RuntimeException e) {
                failures.add(e);
                return null;
            }
        });

        assertThat(decisions).filteredOn(decision -> decision != null)
                .containsExactly(IssuePasswordResetTokenDecision.ISSUED);
        assertThat(failures).singleElement()
                .extracting(PasswordResetConcurrencyIT::sqlStateOf)
                .as("the partial unique index is a real net, and the code is the one of sonda S1")
                .isEqualTo(UNIQUE_VIOLATION);
        assertThat(sent).as("the failed issuance rolled back and sent nothing").hasSize(1);
        assertThat(openTokenCount(account)).isEqualTo(1L);
    }

    /**
     * Scenario I8: two resets with the same token and different passwords, released together.
     * Both read the token as open, then serialize on the account lock; the conditional consume lets
     * exactly one win, the other gets zero rows and a token rejection without paying Argon2id.
     */
    @Test
    void twoConcurrentResetsWithTheSameTokenProduceExactlyOneChange() throws Exception {
        String ana = "ana.martinez@colegio.edu.hn";
        StaffAccountId account = seedStaffAccount(ana, "Cafetal de Copán 2026", false);
        PlainPasswordResetToken token = issueTokenAt(account, "2026-10-15T15:00:00Z");
        String[] passwords = {"Primera contraseña 2026", "Segunda contraseña 2026"};
        CyclicBarrier start = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<ResetPasswordDecision> decisions = new ArrayList<>();
        try {
            List<Future<ResetPasswordDecision>> futures = new ArrayList<>();
            for (String password : passwords) {
                futures.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return resetAt(token.value(), password, new SecondFactorProof.None(),
                            "2026-10-15T15:05:00Z");
                }));
            }
            for (Future<ResetPasswordDecision> future : futures) {
                decisions.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(decisions).extracting(ResetPasswordDecision::outcome)
                .filteredOn(outcome -> outcome instanceof ResetOutcome.Completed).hasSize(1);
        assertThat(decisions).extracting(ResetPasswordDecision::outcome)
                .filteredOn(outcome -> outcome instanceof ResetOutcome.TokenRejected).hasSize(1);
        String winner = decisions.get(0).outcome() instanceof ResetOutcome.Completed
                ? passwords[0] : passwords[1];
        String loser = winner.equals(passwords[0]) ? passwords[1] : passwords[0];
        assertThat(loginAt(ana, winner, "2026-10-15T15:06:00Z"))
                .isInstanceOf(AuthenticationResult.Authenticated.class);
        assertThat(loginAt(ana, loser, "2026-10-15T15:06:30Z"))
                .isInstanceOf(AuthenticationResult.Rejected.class);
        assertThat(auditRows("identity.password_reset.completed")).hasSize(1);
    }

    private List<IssuePasswordResetTokenDecision> runTwice(
            Callable<IssuePasswordResetTokenDecision> body)
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IssuePasswordResetTokenDecision> first = pool.submit(body);
            Future<IssuePasswordResetTokenDecision> second = pool.submit(body);
            List<IssuePasswordResetTokenDecision> decisions = new ArrayList<>();
            decisions.add(first.get(60, TimeUnit.SECONDS));
            decisions.add(second.get(60, TimeUnit.SECONDS));
            return decisions;
        } finally {
            pool.shutdownNow();
        }
    }

    private IssuePasswordResetToken useCase(StaffAccountRepository accounts,
            PasswordResetTokenRepository tokens) {
        return new IssuePasswordResetToken(transactionRunner(), accounts, tokens,
                (institution, accountId, token) -> sent.add(token), new JooqAuditLogWriter(dsl),
                new SecureRandom(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private StaffAccountId seedStaffAccount() {
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        transactionRunner().execute(context(), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, false)
                    """, institutionId.value(), accountId.value(),
                    LoginIdentifier.of("carlos.ramirez@colegio.edu.hn").value(),
                    PLACEHOLDER_PASSWORD_HASH);
            return null;
        });
        return accountId;
    }

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aGFzaA";

    private long openTokenCount(StaffAccountId account) {
        return transactionRunner().execute(context(), () -> dsl.fetchOne("""
                select count(*) as c from identity_password_reset_token
                where institution_id = ? and account_id = ?
                  and consumed_at is null and superseded_at is null
                """, institutionId.value(), account.value()).get("c", Number.class).longValue());
    }

    private static String sqlStateOf(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** Reports the account as present without taking any lock: the defect being simulated. */
    private record NonLockingAccounts(InstitutionId institutionId, StaffAccountId accountId)
            implements StaffAccountRepository {

        @Override
        public Optional<StaffAccount> findBy(InstitutionId institution,
                LoginIdentifier identifier) {
            throw new UnsupportedOperationException("issuance never looks an account up by address");
        }

        @Override
        public Optional<StaffAccount> lockById(InstitutionId institution, StaffAccountId account) {
            return Optional.of(new StaffAccount(account, institution,
                    LoginIdentifier.of("carlos.ramirez@colegio.edu.hn"),
                    new StoredPasswordHash(PLACEHOLDER_PASSWORD_HASH), false));
        }

        @Override
        public boolean replacePasswordHash(InstitutionId institution, StaffAccountId account,
                StoredPasswordHash newHash) {
            throw new UnsupportedOperationException("issuance never changes a password");
        }
    }

    /** The real adapter, except that each insert waits until both issuances have reached it. */
    private record BarrierBeforeInsert(PasswordResetTokenRepository delegate,
            CyclicBarrier barrier) implements PasswordResetTokenRepository {

        @Override
        public long countIssuedSince(InstitutionId institutionId, StaffAccountId accountId,
                Instant since) {
            return delegate.countIssuedSince(institutionId, accountId, since);
        }

        @Override
        public int supersedeOpen(InstitutionId institutionId, StaffAccountId accountId,
                Instant at) {
            return delegate.supersedeOpen(institutionId, accountId, at);
        }

        @Override
        public void insert(InstitutionId institutionId, UUID id, StaffAccountId accountId,
                PasswordResetTokenHash hash, Instant issuedAt, Instant expiresAt) {
            try {
                barrier.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException(e);
            }
            delegate.insert(institutionId, id, accountId, hash, issuedAt, expiresAt);
        }

        @Override
        public Optional<PasswordResetTokenRow> findByHash(InstitutionId institutionId,
                PasswordResetTokenHash hash) {
            return delegate.findByHash(institutionId, hash);
        }

        @Override
        public boolean consume(InstitutionId institutionId, UUID id, Instant at) {
            return delegate.consume(institutionId, id, at);
        }
    }
}
