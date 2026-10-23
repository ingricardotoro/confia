package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.EnrollTotpSecondFactorResult.AlreadyEnrolled;
import com.confia.identity.application.EnrollTotpSecondFactorResult.Enrolled;
import com.confia.identity.application.SecondFactorOutcome.Rejected;
import com.confia.identity.application.SecondFactorOutcome.Started;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqRecoveryCodeRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.identity.infrastructure.JooqTotpVerificationBackoffStore;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecondFactorPurpose;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * An enrollment and a first verification of the same account at the same time
 * (session-endpoints-cookie-and-csrf design.md, section 4 and decision 13): both take the lock of the
 * account first, so whoever comes second finds what the first left. If the verification is first,
 * the credential is verified when the enrollment looks at it and the enrollment is refused without
 * touching the secret; if the enrollment is first, it replaced the secret before the verification
 * reads it, and a code of the old secret is refused. Two real transactions on a real PostgreSQL; no
 * clock decides the order: the one that must be first is held right after it takes the lock, and the
 * test goes on when the database reports a backend waiting for the lock of the account.
 */
class EnrollVersusVerifyConcurrencyIT extends RevocationIntegrationTest {

    private static final String JORGE = "jorge.aguilar@colegio.edu.hn";
    private static final String T0 = "2026-10-22T10:00:00Z";

    /** The verification owns the lock: the enrollment waits, finds the credential verified and is refused. */
    @Test
    void anEnrollmentThatWaitsForTheFirstVerificationIsRefusedAndTheSecretStands() throws Exception {
        StaffAccountId jorge = seedStaffAccount(JORGE, CURRENT_PASSWORD, true);
        Enrolled first = enrollWithoutVerifying(jorge);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompleteSecondFactor verifying = completion(
                new HoldingAccounts(new JooqStaffAccountRepository(dsl), locked, release));
        CompleteSecondFactorCommand command = new CompleteSecondFactorCommand(jorge.value(),
                institutionId.value(), SecondFactorPurpose.ENROLL,
                validCodeAt(first.secret(), T0).value(), null, null, AGENT_ONE,
                UUID.randomUUID().toString());

        Race<CompleteSecondFactorDecision, EnrollTotpSecondFactorResult> race = raceOnTheAccount(locked, release,
                () -> verifying.execute(command),
                () -> enrollment().execute(context(), jorge));

        assertThat(race.waiterWaited()).as("the enrollment waited for the lock of the account").isTrue();
        assertThat(race.holder().outcome()).isInstanceOf(Started.class);
        assertThat(race.waiter()).isInstanceOf(AlreadyEnrolled.class);
        assertThat(isVerified(jorge)).isTrue();
        assertThat(unusedRecoveryCodes(jorge)).as("the codes of the verified credential stay usable")
                .isEqualTo(10L);
        assertThat(auditRows("identity.mfa.enrollment_rejected")).hasSize(1);
        assertThat(auditRows("identity.mfa.enrolled")).as("only the first enrollment").hasSize(1);
    }

    /** The enrollment owns the lock: the verification waits and finds the secret replaced. */
    @Test
    void aVerificationThatWaitsForAReenrollmentFindsTheNewSecretAndRefusesTheOldCode()
            throws Exception {
        StaffAccountId jorge = seedStaffAccount(JORGE, CURRENT_PASSWORD, true);
        Enrolled first = enrollWithoutVerifying(jorge);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EnrollTotpSecondFactor enrolling = enrollment(
                new HoldingAccounts(new JooqStaffAccountRepository(dsl), locked, release));
        CompleteSecondFactorCommand command = new CompleteSecondFactorCommand(jorge.value(),
                institutionId.value(), SecondFactorPurpose.ENROLL,
                validCodeAt(first.secret(), T0).value(), null, null, AGENT_ONE,
                UUID.randomUUID().toString());

        Race<EnrollTotpSecondFactorResult, CompleteSecondFactorDecision> race = raceOnTheAccount(locked, release,
                () -> enrolling.execute(context(), jorge),
                () -> completion(new JooqStaffAccountRepository(dsl)).execute(command));

        assertThat(race.waiterWaited()).as("the verification waited for the lock of the account").isTrue();
        Enrolled second = (Enrolled) race.holder();
        assertThat(second.replacedUnverified()).isTrue();
        assertThat(race.waiter().outcome())
                .as("a code of the replaced secret proves nothing").isInstanceOf(Rejected.class);
        assertThat(isVerified(jorge)).isFalse();
        assertThat(familiesIn(institutionId, jorge)).isEmpty();
        assertThat(unusedRecoveryCodes(jorge)).as("the codes of the new secret").isEqualTo(10L);
    }

    /**
     * Runs {@code holder}, which must count {@code locked} down once it owns the lock of the account
     * and then wait for {@code release}; starts {@code waiter} once that happened; goes on when the
     * database reports a backend waiting for a row lock on the account (a condition with a bound,
     * never a pause); and releases the holder.
     */
    private <A, B> Race<A, B> raceOnTheAccount(CountDownLatch locked, CountDownLatch release, Callable<A> holder,
            Callable<B> waiter) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<A> first = pool.submit(holder);
                assertThat(locked.await(30, TimeUnit.SECONDS))
                        .as("the first transaction took the lock of the account").isTrue();
                Future<B> second = pool.submit(waiter);
                boolean waited = holdsWithin(20_000, () -> second.isDone()
                        || someBackendWaitsForTheAccountLock()) && !second.isDone();
                release.countDown();
                return new Race<>(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS),
                        waited);
            } finally {
                release.countDown();
            }
        }
    }

    private EnrollTotpSecondFactor enrollment() {
        return enrollment(new JooqStaffAccountRepository(dsl));
    }

    private EnrollTotpSecondFactor enrollment(StaffAccountRepository accounts) {
        return new EnrollTotpSecondFactor(transactionRunner(), accounts,
                new JooqTotpCredentialRepository(dsl), new JooqRecoveryCodeRepository(dsl),
                new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), PEPPER),
                encryptionService(), new JooqAuditLogWriter(dsl), fixedClock(T0));
    }

    private CompleteSecondFactor completion(StaffAccountRepository accounts) {
        Clock clock = fixedClock(T0);
        JooqAuditLogWriter writer = new JooqAuditLogWriter(dsl);
        VerifyTotpCode verifyTotp = new VerifyTotpCode(transactionRunner(),
                new JooqTotpCredentialRepository(dsl), new JooqTotpVerificationBackoffStore(dsl),
                encryptionService(), writer, clock);
        ConsumeRecoveryCode consume = new ConsumeRecoveryCode(transactionRunner(),
                new JooqRecoveryCodeRepository(dsl),
                new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), PEPPER), writer, clock);
        return new CompleteSecondFactor(transactionRunner(), accounts,
                new JooqTotpCredentialRepository(dsl), verifyTotp, consume,
                new JooqTotpVerificationBackoffStore(dsl), new JooqAccountSessionRevoker(dsl),
                issueSession(clock, writer, new JooqRefreshTokenRepository(dsl)), writer, clock);
    }

    private boolean isVerified(StaffAccountId account) {
        return transactionRunner().execute(context(), () -> new JooqTotpCredentialRepository(dsl)
                .findByAccountId(institutionId, account)).orElseThrow().isVerified();
    }

    private long unusedRecoveryCodes(StaffAccountId account) {
        return transactionRunner().execute(context(), () -> new JooqRecoveryCodeRepository(dsl)
                .countUnusedByAccountId(institutionId, account));
    }
}
