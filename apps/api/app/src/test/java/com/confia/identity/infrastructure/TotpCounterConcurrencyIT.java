package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.TotpCredentialRepository;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The concurrency half of {@link TotpCredentialRepository#acceptCounter} (design.md decision 7,
 * step 5a: "0 filas = derrota concurrente, se trata como rechazo aunque el código fuera válido").
 * Two independent {@link TransactionRunner} instances, each on its own connection, synchronized with
 * a {@link CyclicBarrier} right after each opens its own transaction and before either accepts the
 * same counter — the same placement {@code LoginBackoffConcurrencyIT} and {@code
 * IdempotentExecutorConcurrencyIT} already establish, never a wall-clock race.
 *
 * <p><b>Why this test lives at the adapter and not on {@code VerifyTotpCode}.</b> Through the use
 * case the contested case is unreachable: {@code TotpVerificationPolicy} already rejects a replay
 * within one session, and two concurrent verifications of one account serialize earlier still, at
 * {@code JooqTotpVerificationBackoffStore.claim}'s row lock. Proving the predicate therefore needs
 * two callers that skip the backoff claim, which is what this class does. Full reasoning, and the
 * negative control that removing the predicate makes both callers accept, in apply-progress.md.
 */
class TotpCounterConcurrencyIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    /** Any counter greater than the {@code -1} sentinel {@code V6} seeds; its value is irrelevant,
     * only that both callers present the very same one. */
    private static final long CONTESTED_COUNTER = 10L;

    @Test
    void onlyOneOfTwoConcurrentCallersAcceptsTheSameCounter() throws Exception {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        seedCredential(institutionId, accountId);

        CyclicBarrier bothTransactionsOpenBeforeEitherAccepts = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> futureA = executor.submit(acceptTask(institutionId, accountId,
                    bothTransactionsOpenBeforeEitherAccepts));
            Future<Boolean> futureB = executor.submit(acceptTask(institutionId, accountId,
                    bothTransactionsOpenBeforeEitherAccepts));

            List<Boolean> outcomes = List.of(futureA.get(30, TimeUnit.SECONDS),
                    futureB.get(30, TimeUnit.SECONDS));

            assertThat(outcomes)
                    .as("exactly one caller must accept the contested counter and the other must "
                            + "affect zero rows: both reading last_accepted_counter = -1 and both "
                            + "succeeding would be a replay accepted twice, which is the whole "
                            + "point of the predicate")
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }

        long finalCounter = transactionRunner().execute(contextOf(institutionId),
                () -> dsl.fetchOne("""
                        select last_accepted_counter as c from identity_mfa_totp_credential
                        where institution_id = ? and account_id = ?
                        """, institutionId.value(), accountId.value())
                        .get("c", Number.class).longValue());
        assertThat(finalCounter)
                .as("the winner's counter must stand, neither lost nor advanced twice")
                .isEqualTo(CONTESTED_COUNTER);
    }

    private Callable<Boolean> acceptTask(InstitutionId institutionId, StaffAccountId accountId,
            CyclicBarrier barrier) {
        TransactionRunner independentRunner = new TransactionRunner(transactionManager(), dataSource());
        TotpCredentialRepository repository = new JooqTotpCredentialRepository(dsl);
        SecurityContext context = contextOf(institutionId);
        return () -> independentRunner.execute(context, () -> {
            awaitUninterruptibly(barrier);
            return repository.acceptCounter(institutionId, accountId, CONTESTED_COUNTER);
        });
    }

    private void seedCredential(InstitutionId institutionId, StaffAccountId accountId) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, true)
                    """, institutionId.value(), accountId.value(),
                    "mfa." + accountId.value() + "@colegio.edu.hn", PLACEHOLDER_PASSWORD_HASH);
            new JooqTotpCredentialRepository(dsl).insert(institutionId, accountId,
                    "v1:" + UUID.randomUUID() + ":aXY=:Y3Q=:dGFn");
            return null;
        });
    }

    private static void awaitUninterruptibly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("both sides of the race must reach the barrier", e);
        }
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
