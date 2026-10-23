package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.EnrollTotpSecondFactorResult.Enrolled;
import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.RecoveryCodeRow;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher;
import com.confia.identity.infrastructure.JooqRecoveryCodeRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scenario I183 (session-endpoints-cookie-and-csrf design.md, decision 13 and D-N2): two enrollments
 * of the same account that never verified its credential, at the same time, on a real PostgreSQL
 * with real transactions, each on its own connection. The lock of the account makes them one
 * enrollment and one re-enrollment, one after the other: both answer {@code Enrolled}, there is one
 * credential, the secret that stands is the one of whoever came last, and the codes still unused are
 * only that one's. Neither is a uniqueness violation, and neither is refused.
 *
 * <p>No clock decides the order. In the first test the transaction that must be first is held with a
 * {@link CountDownLatch} right after it takes the lock; the other is started, and the test goes on
 * when the database reports a backend waiting for a row lock on the account (a condition with a bound,
 * never a pause). In the second, a barrier releases both together with no lock held by the test, which
 * is the shape that ends in a uniqueness violation when the account lock is missing.
 */
class ConcurrentEnrollmentIT extends PasswordResetIntegrationTest {

    private static final String JORGE = "jorge.aguilar@colegio.edu.hn";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    /** Two enrollments, the second started while the first holds the lock of the account. */
    @Test
    void anEnrollmentThatWaitsForTheLockOfTheAccountIsAReenrollmentAndItsSecretStands()
            throws Exception {
        StaffAccountId jorge = seedStaffAccount(JORGE, "Cafetal de Copán 2026", true);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EnrollTotpSecondFactor holding = enrollment(
                new HoldingAccounts(new JooqStaffAccountRepository(dsl), locked, release));
        EnrollTotpSecondFactor waiting = enrollment(new JooqStaffAccountRepository(dsl));

        Enrolled first;
        Enrolled second;
        boolean waited;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<EnrollTotpSecondFactorResult> holder = pool.submit(
                        () -> holding.execute(context(), jorge));
                assertThat(locked.await(30, TimeUnit.SECONDS))
                        .as("the first enrollment took the lock of the account").isTrue();
                Future<EnrollTotpSecondFactorResult> waiter = pool.submit(
                        () -> waiting.execute(context(), jorge));
                waited = SessionIssuanceIntegrationTest.holdsWithin(20_000, () -> waiter.isDone()
                        || RotationIntegrationTest.someBackendWaitsForALockOn("identity_staff_account"))
                        && !waiter.isDone();
                release.countDown();
                first = (Enrolled) holder.get(60, TimeUnit.SECONDS);
                second = (Enrolled) waiter.get(60, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }

        assertThat(waited).as("the second enrollment waited for the lock the first held").isTrue();
        assertThat(first.replacedUnverified()).as("the first one found no credential").isFalse();
        assertThat(second.replacedUnverified()).as("the second one replaced the first").isTrue();
        assertTheLastOneStands(jorge, first, second);
    }

    /** Two enrollments released together: whichever takes the lock first, one stands and nothing fails. */
    @Test
    void twoEnrollmentsReleasedTogetherAreAnEnrollmentAndAReenrollmentNeverAUniquenessViolation()
            throws Exception {
        StaffAccountId jorge = seedStaffAccount(JORGE, "Cafetal de Copán 2026", true);
        EnrollTotpSecondFactor useCase = enrollment(new JooqStaffAccountRepository(dsl));
        CyclicBarrier start = new CyclicBarrier(2);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        List<Enrolled> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<EnrollTotpSecondFactorResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return useCase.execute(context(), jorge);
                }));
            }
            for (Future<EnrollTotpSecondFactorResult> future : futures) {
                try {
                    results.add((Enrolled) future.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        }

        assertThat(failures).as("neither enrollment fails: no 409, no 500, no uniqueness violation")
                .isEmpty();
        assertThat(results).hasSize(2);
        Enrolled replaced = results.stream().filter(Enrolled::replacedUnverified).findFirst()
                .orElseThrow();
        Enrolled initial = results.stream().filter(result -> !result.replacedUnverified())
                .findFirst().orElseThrow();
        assertTheLastOneStands(jorge, initial, replaced);
    }

    private void assertTheLastOneStands(StaffAccountId account, Enrolled first, Enrolled last) {
        assertThat(count("identity_mfa_totp_credential", account))
                .as("exactly one credential").isEqualTo(1L);
        assertThat(storedBase32(account)).as("the secret that stands is the last one's")
                .isEqualTo(last.secret().base32())
                .isNotEqualTo(first.secret().base32());
        List<RecoveryCodeRow> unused = transactionRunner().execute(context(),
                () -> new JooqRecoveryCodeRepository(dsl).findUnusedByAccountId(institutionId,
                        account));
        BouncyCastleRecoveryCodeHasher hasher =
                new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), PEPPER);
        assertThat(unused).as("the codes still unused are the ten of the last enrollment").hasSize(10);
        assertThat(unused).allMatch(row -> last.recoveryCodes().stream()
                .anyMatch(code -> hasher.matches(code, row.hash())));
        assertThat(first.recoveryCodes()).as("the codes of the first were retired").noneMatch(
                code -> unused.stream().anyMatch(row -> hasher.matches(code, row.hash())));
        assertThat(count("identity_mfa_recovery_code", account)).isEqualTo(20L);

        List<AuditRowSnapshot> enrolled = auditRows("identity.mfa.enrolled");
        assertThat(enrolled).as("one enrollment and one re-enrollment").hasSize(2);
        assertThat(enrolled.stream().filter(row -> row.afterValue() != null).toList())
                .singleElement().satisfies(row -> {
                    assertThat(JSON.readTree(row.afterValue()).get("replacedUnverified")
                            .asBoolean()).isTrue();
                    assertThat(JSON.readTree(row.afterValue()).get("invalidatedRecoveryCodes")
                            .asInt()).isEqualTo(10);
                });
        assertThat(transactionRunner().execute(context(), () -> new JooqTotpCredentialRepository(dsl)
                .findByAccountId(institutionId, account)).orElseThrow().isVerified())
                .as("the credential is still unverified").isFalse();
    }

    private EnrollTotpSecondFactor enrollment(StaffAccountRepository accounts) {
        return new EnrollTotpSecondFactor(transactionRunner(), accounts,
                new JooqTotpCredentialRepository(dsl), new JooqRecoveryCodeRepository(dsl),
                new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), PEPPER),
                encryptionService(), new JooqAuditLogWriter(dsl), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String storedBase32(StaffAccountId account) {
        return transactionRunner().execute(context(), () -> {
            String encrypted = dsl.fetchOne("""
                    select encrypted_secret from identity_mfa_totp_credential
                    where institution_id = ? and account_id = ?
                    """, institutionId.value(), account.value()).get("encrypted_secret", String.class);
            try {
                return PlainTotpSecret.of(encryptionService().decrypt("identity_mfa_totp_credential",
                        "encrypted_secret", institutionId, institutionId.value() + ":" + account.value(),
                        encrypted)).base32();
            } catch (com.confia.kernel.AeadIntegrityException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private long count(String table, StaffAccountId account) {
        return transactionRunner().execute(context(), () -> dsl.fetchOne(
                "select count(*) as n from " + table + " where institution_id = ? and account_id = ?",
                institutionId.value(), account.value()).get("n", Long.class));
    }
}
