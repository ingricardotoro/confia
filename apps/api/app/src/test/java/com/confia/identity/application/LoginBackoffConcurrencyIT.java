package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.AuthenticationResult.Rejected;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The concurrency half of design.md decision 9 and decision 5 (specs/identity/spec.md, requirement
 * "Estado del retroceso persistido en PostgreSQL...", escenario "Dos intentos fallidos concurrentes
 * contra la misma cuenta no pierden ninguna escritura"). Two independent {@link TransactionRunner}
 * instances, each on its own connection, synchronized with a {@link CyclicBarrier} right after each
 * opens its own transaction and before either calls {@link
 * AuthenticateWithPassword#runWithinTransaction} — the same placement {@code TransactionRunnerRetryIT}
 * and {@code IdempotentExecutorConcurrencyIT} already establish, never a wall-clock race.
 *
 * <p>The reclaim statement's own row lock (design.md decision 5, sonda S3) is what serializes the
 * two attempts: whichever loses the race blocks at its own {@code INSERT ... ON CONFLICT} until the
 * winner commits, then sees the winner's already-saved state. Losing that serialization would show
 * up here as a lost write — both attempts reading the same prior state and producing the same
 * ordinal — which the final counter assertion below would catch.
 */
class LoginBackoffConcurrencyIT extends CommittingPostgresIntegrationTest {

    private static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));
    private static final BouncyCastleArgon2PasswordHasher HASHER =
            new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), PEPPER);
    private static final HmacLoginIdentifierFingerprinter FINGERPRINTER =
            new HmacLoginIdentifierFingerprinter(PEPPER);

    @Test
    void twoConcurrentFailedAttemptsAgainstTheSameAccountLoseNoWrite() throws Exception {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("carlos.ramirez@colegio.edu.hn");
        IdentifierFingerprint fingerprint = FINGERPRINTER.fingerprintOf(identifier);
        seedStaffAccount(institutionId, identifier);
        seedBackoff(institutionId, fingerprint, 1, Instant.parse("2026-03-10T14:00:00Z"));

        CyclicBarrier bothTransactionsOpenBeforeEitherClaims = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AuthenticationDecision> futureA = executor.submit(attemptTask(institutionId,
                    identifier, "2026-03-10T14:00:05Z", bothTransactionsOpenBeforeEitherClaims));
            Future<AuthenticationDecision> futureB = executor.submit(attemptTask(institutionId,
                    identifier, "2026-03-10T14:00:06Z", bothTransactionsOpenBeforeEitherClaims));

            AuthenticationDecision decisionA = futureA.get(30, TimeUnit.SECONDS);
            AuthenticationDecision decisionB = futureB.get(30, TimeUnit.SECONDS);

            assertThat(decisionA.result()).isInstanceOf(Rejected.class);
            assertThat(decisionB.result()).isInstanceOf(Rejected.class);
            assertThat(Set.of(decisionA.requiredDelay(), decisionB.requiredDelay()))
                    .as("one attempt must see the prior state (ordinal 2, no delay yet) and the "
                            + "other the already-advanced state (ordinal 3, 1 second) — if either "
                            + "had lost the other's write, both would compute the very same delay")
                    .containsExactlyInAnyOrder(Duration.ZERO, Duration.ofSeconds(1));
        } finally {
            executor.shutdownNow();
        }

        BackoffState finalState = transactionRunner().execute(contextOf(institutionId),
                () -> new JooqLoginBackoffStore(dsl).claim(institutionId, fingerprint,
                        Instant.parse("2026-03-10T14:00:07Z")));
        assertThat(finalState.consecutiveFailures())
                .as("one failure already registered, plus the two concurrent ones: three, with "
                        + "neither lost")
                .isEqualTo(3);
    }

    private Callable<AuthenticationDecision> attemptTask(InstitutionId institutionId,
            LoginIdentifier identifier, String instant, CyclicBarrier barrier) {
        TransactionRunner independentRunner = new TransactionRunner(transactionManager(), dataSource());
        AuthenticateWithPassword useCase = new AuthenticateWithPassword(independentRunner,
                () -> institutionId, new JooqStaffAccountRepository(dsl),
                new JooqLoginBackoffStore(dsl), HASHER, FINGERPRINTER, new JooqAuditLogWriter(dsl),
                fixedClock(instant));
        SecurityContext context = contextOf(institutionId);
        return () -> independentRunner.execute(context, () -> {
            awaitUninterruptibly(barrier);
            return useCase.runWithinTransaction(institutionId, context.requestId(),
                    new AuthenticationCommand(identifier.value(), "incorrecta"));
        });
    }

    private void seedStaffAccount(InstitutionId institutionId, LoginIdentifier identifier) {
        StoredPasswordHash hash = HASHER.hash(PlainPassword.of("Correcta#2026-C3b"));
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, false)
                    """, institutionId.value(), UUID.randomUUID(), identifier.value(), hash.value());
            return null;
        });
    }

    private void seedBackoff(InstitutionId institutionId, IdentifierFingerprint fingerprint,
            int consecutiveFailures, Instant lastAttemptAt) {
        JooqLoginBackoffStore store = new JooqLoginBackoffStore(dsl);
        transactionRunner().execute(contextOf(institutionId), () -> {
            store.claim(institutionId, fingerprint, lastAttemptAt);
            store.save(institutionId, fingerprint, new BackoffState(consecutiveFailures, lastAttemptAt));
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

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
