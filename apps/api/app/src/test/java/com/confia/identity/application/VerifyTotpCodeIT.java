package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.TotpAlgorithm;
import com.confia.identity.domain.TotpCode;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.identity.infrastructure.JooqTotpVerificationBackoffStore;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.crypto.ColumnEncryptionMasterKey;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.infrastructure.JooqDataEncryptionKeyRepository;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link VerifyTotpCode} end to end, against a real PostgreSQL (column-encryption-and-mfa-totp
 * design.md §4.2; specs/identity/spec.md, requirement "Inscripción y verificación del segundo
 * factor TOTP..."). Seeds the credential directly through {@link JooqTotpCredentialRepository}
 * (never through {@code EnrollTotpSecondFactor}, which does not exist until cut C3 — the task's own
 * discrepancy resolution).
 */
class VerifyTotpCodeIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";
    private static final Duration PERIOD = Duration.ofSeconds(30);

    /** One fixed master key per test instance: every {@link #encryptionService()} call within the
     * same test method must unwrap the very same data-encryption key that {@link #seedCredential}
     * wrapped it under — a fresh random key per call would make decryption fail with an unrelated
     * {@link com.confia.kernel.AeadIntegrityException} instead of exercising this use case. */
    private final ColumnEncryptionMasterKey masterKey = randomMasterKey();

    @Test
    void aCodeWithinTheToleranceWindowIsAccepted() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        PlainTotpSecret secret = seedCredential(institutionId, accountId);
        Instant now = Instant.parse("2026-10-05T08:00:00Z");
        long counterOneBehind = TotpAlgorithm.counterFor(now, PERIOD) - 1;
        TotpCode code = TotpAlgorithm.generate(secret.value(), counterOneBehind);

        VerifyTotpCodeDecision decision = verify(institutionId, accountId, code, now);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.requiredDelay()).isEqualTo(Duration.ZERO);
    }

    /**
     * specs/identity/spec.md, "Un código ya aceptado no puede reutilizarse, aunque siga siendo
     * matemáticamente válido": presenting the very same, already-accepted code a second time must
     * be rejected end to end, through the real conditional {@code UPDATE}.
     */
    @Test
    void theSameCodeCannotBeAcceptedTwice() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        PlainTotpSecret secret = seedCredential(institutionId, accountId);
        Instant now = Instant.parse("2026-10-05T08:00:00Z");
        long counter = TotpAlgorithm.counterFor(now, PERIOD);
        TotpCode code = TotpAlgorithm.generate(secret.value(), counter);

        VerifyTotpCodeDecision first = verify(institutionId, accountId, code, now);
        assertThat(first.accepted()).isTrue();

        VerifyTotpCodeDecision second = verify(institutionId, accountId, code,
                now.plusSeconds(5));
        assertThat(second.accepted())
                .as("the same code, mathematically valid for the same counter, must be rejected "
                        + "once that counter has already been accepted")
                .isFalse();
    }

    private VerifyTotpCodeDecision verify(InstitutionId institutionId, StaffAccountId accountId,
            TotpCode code, Instant now) {
        VerifyTotpCode useCase = new VerifyTotpCode(transactionRunner(),
                new JooqTotpCredentialRepository(dsl), new JooqTotpVerificationBackoffStore(dsl),
                encryptionService(), new JooqAuditLogWriter(dsl), Clock.fixed(now, ZoneOffset.UTC));
        return useCase.execute(contextOf(institutionId), accountId, code);
    }

    private PlainTotpSecret seedCredential(InstitutionId institutionId, StaffAccountId accountId) {
        PlainTotpSecret secret = PlainTotpSecret.generate(new SecureRandom());
        ColumnEncryptionService encryption = encryptionService();
        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            String encrypted = encryption.encryptForNewValue("identity_mfa_totp_credential",
                    "encrypted_secret", institutionId, rowIdOf(institutionId, accountId),
                    secret.value());
            new JooqTotpCredentialRepository(dsl).insert(institutionId, accountId, encrypted);
            return null;
        });
        return secret;
    }

    private ColumnEncryptionService encryptionService() {
        return new ColumnEncryptionService(
                new JooqDataEncryptionKeyRepository(dsl, new AesGcmCipher(), masterKey),
                new AesGcmCipher());
    }

    private static ColumnEncryptionMasterKey randomMasterKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return ColumnEncryptionMasterKey.fromBase64(Base64.getEncoder().encodeToString(key));
    }

    private void seedAccount(InstitutionId institutionId, StaffAccountId accountId) {
        dsl.execute("""
                insert into identity_staff_account
                    (institution_id, id, email, password_hash, mfa_required)
                values (?, ?, ?, ?, true)
                """, institutionId.value(), accountId.value(),
                "mfa." + accountId.value() + "@colegio.edu.hn", PLACEHOLDER_PASSWORD_HASH);
    }

    /** {@code institutionId:accountId} (design.md, decision 5). */
    private static String rowIdOf(InstitutionId institutionId, StaffAccountId accountId) {
        return institutionId.value() + ":" + accountId.value();
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
