package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher;
import com.confia.identity.infrastructure.JooqRecoveryCodeRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ConsumeRecoveryCode} end to end, against a real PostgreSQL
 * (column-encryption-and-mfa-totp design.md, §4.3; specs/identity/spec.md, requirement "Diez
 * códigos de recuperación de MFA..."). Seeds ten real, hashed codes through {@link
 * EnrollTotpSecondFactor} itself — never by hand — so the hashes this test consumes against are
 * exactly what production enrollment would have written.
 */
class ConsumeRecoveryCodeIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";
    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    private final ColumnEncryptionMasterKey masterKey = randomMasterKey();
    private final Argon2Pepper pepper =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void usingOneCodeInvalidatesItWithoutAffectingTheOtherNine() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            return null;
        });
        List<PlainRecoveryCode> codes = enroll(institutionId, accountId).recoveryCodes();
        PlainRecoveryCode usedCode = codes.getFirst();

        ConsumeRecoveryCodeDecision decision = consume(institutionId, accountId, usedCode);

        assertThat(decision.accepted()).isTrue();

        long stillUnused = transactionRunner().execute(contextOf(institutionId), () -> dsl
                .fetchOne("""
                        select count(*) as c from identity_mfa_recovery_code
                        where institution_id = ? and account_id = ? and used_at is null
                        """, institutionId.value(), accountId.value())
                .get("c", Long.class));
        assertThat(stillUnused)
                .as("the nine codes not used remain valid for a future use")
                .isEqualTo(9L);
    }

    @Test
    void theSameCodeCannotBeAcceptedTwice() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            return null;
        });
        List<PlainRecoveryCode> codes = enroll(institutionId, accountId).recoveryCodes();
        PlainRecoveryCode usedCode = codes.getFirst();

        ConsumeRecoveryCodeDecision first = consume(institutionId, accountId, usedCode);
        assertThat(first.accepted()).isTrue();

        ConsumeRecoveryCodeDecision second = consume(institutionId, accountId, usedCode);
        assertThat(second.accepted())
                .as("a code already used must be rejected on a second attempt")
                .isFalse();
    }

    private EnrollTotpSecondFactorResult enroll(InstitutionId institutionId, StaffAccountId accountId) {
        EnrollTotpSecondFactor useCase = new EnrollTotpSecondFactor(transactionRunner(),
                new JooqTotpCredentialRepository(dsl), new JooqRecoveryCodeRepository(dsl),
                recoveryCodeHasher(), encryptionService(), new JooqAuditLogWriter(dsl));
        return useCase.execute(contextOf(institutionId), accountId);
    }

    private ConsumeRecoveryCodeDecision consume(InstitutionId institutionId, StaffAccountId accountId,
            PlainRecoveryCode presented) {
        ConsumeRecoveryCode useCase = new ConsumeRecoveryCode(transactionRunner(),
                new JooqRecoveryCodeRepository(dsl), recoveryCodeHasher(),
                new JooqAuditLogWriter(dsl), Clock.fixed(NOW, ZoneOffset.UTC));
        return useCase.execute(contextOf(institutionId), accountId, presented);
    }

    private BouncyCastleRecoveryCodeHasher recoveryCodeHasher() {
        return new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), pepper);
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

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
