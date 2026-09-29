package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.identity.infrastructure.JooqRecoveryCodeRepository;
import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogReader;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.crypto.ColumnEncryptionMasterKey;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.infrastructure.JooqDataEncryptionKeyRepository;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link EnrollTotpSecondFactor} end to end, against a real PostgreSQL
 * (column-encryption-and-mfa-totp design.md, §4.1; specs/identity/spec.md, requirements
 * "Inscripción y verificación del segundo factor TOTP..." and "Diez códigos de recuperación de
 * MFA...", escenario "Los diez códigos se generan y se muestran una única vez").
 */
class EnrollTotpSecondFactorIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    private final ColumnEncryptionMasterKey masterKey = randomMasterKey();
    private final Argon2Pepper pepper =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void enrollingGeneratesTenDistinctRecoveryCodesShownOnce() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            return null;
        });

        EnrollTotpSecondFactorResult result = enroll(institutionId, accountId);

        assertThat(result.recoveryCodes())
                .as("exactly ten distinct recovery codes, returned once in clear text")
                .hasSize(10)
                .doesNotHaveDuplicates();
    }

    @Test
    void theEncryptedCredentialStartsAtCounterMinusOneAndDecryptsBackToTwentyBytes() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            return null;
        });

        enroll(institutionId, accountId);

        transactionRunner().execute(contextOf(institutionId), () -> {
            var row = dsl.fetchOne("""
                    select last_accepted_counter, encrypted_secret
                    from identity_mfa_totp_credential
                    where institution_id = ? and account_id = ?
                    """, institutionId.value(), accountId.value());
            assertThat(row.get("last_accepted_counter", Long.class)).isEqualTo(-1L);
            String encryptedSecret = row.get("encrypted_secret", String.class);
            assertThat(encryptedSecret).startsWith("v1:");
            byte[] decrypted;
            try {
                decrypted = encryptionService().decrypt("identity_mfa_totp_credential",
                        "encrypted_secret", institutionId,
                        institutionId.value() + ":" + accountId.value(), encryptedSecret);
            } catch (AeadIntegrityException e) {
                throw new IllegalStateException("stored TOTP secret failed AEAD verification", e);
            }
            assertThat(decrypted).hasSize(20);
            return null;
        });
    }

    @Test
    void noSubsequentQueryCanRecoverTheCodesInClearText() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            return null;
        });

        EnrollTotpSecondFactorResult result = enroll(institutionId, accountId);

        List<String> storedHashes = transactionRunner().execute(contextOf(institutionId), () -> dsl
                .fetch("""
                        select code_hash from identity_mfa_recovery_code
                        where institution_id = ? and account_id = ?
                        """, institutionId.value(), accountId.value())
                .stream()
                .map(record -> record.get("code_hash", String.class))
                .collect(Collectors.toList()));

        assertThat(storedHashes).hasSize(10).allMatch(hash -> hash.startsWith("$argon2id$"));
        List<String> plainValues =
                result.recoveryCodes().stream().map(PlainRecoveryCode::value).toList();
        assertThat(storedHashes).noneMatch(plainValues::contains);
    }

    @Test
    void enrollmentAuditsExactlyOneEnrolledEvent() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            return null;
        });

        enroll(institutionId, accountId);

        List<AuditRowSnapshot> auditRows = auditRowsFor(institutionId, accountId);
        List<AuditRowSnapshot> enrolledRows = auditRows.stream()
                .filter(row -> row.action().equals("identity.mfa.enrolled"))
                .toList();
        assertThat(enrolledRows)
                .as("a single identity.mfa.enrolled event covers both the credential and the ten "
                        + "recovery codes, never one row per code")
                .hasSize(1);
    }

    private EnrollTotpSecondFactorResult enroll(InstitutionId institutionId, StaffAccountId accountId) {
        EnrollTotpSecondFactor useCase = new EnrollTotpSecondFactor(transactionRunner(),
                new JooqTotpCredentialRepository(dsl), new JooqRecoveryCodeRepository(dsl),
                new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), pepper),
                encryptionService(), new JooqAuditLogWriter(dsl));
        return useCase.execute(contextOf(institutionId), accountId);
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

    private List<AuditRowSnapshot> auditRowsFor(InstitutionId institutionId,
            StaffAccountId accountId) {
        AuditLogReader reader = new JooqAuditLogReader(dsl);
        return transactionRunner()
                .execute(contextOf(institutionId), () -> reader.pageOf(institutionId, 0, 100))
                .stream()
                .filter(row -> row.entityId().equals(accountId.value().toString()))
                .toList();
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
