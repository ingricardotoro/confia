package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.EnrollTotpSecondFactorResult.AlreadyEnrolled;
import com.confia.identity.application.EnrollTotpSecondFactorResult.Enrolled;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher;
import com.confia.identity.infrastructure.JooqRecoveryCodeRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link EnrollTotpSecondFactor} end to end, against a real PostgreSQL
 * (column-encryption-and-mfa-totp design.md, §4.1; session-endpoints-cookie-and-csrf design.md,
 * decision 13; specs/identity/spec.md, requirements "Inscripción y verificación del segundo factor
 * TOTP...", "Diez códigos de recuperación de MFA..." and "La inscripción del segundo factor ... es
 * repetible hasta la primera verificación"). The enrollment is repeatable while the credential was
 * never verified, and refused, with a trace, once it was.
 */
class EnrollTotpSecondFactorIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";
    private static final String TABLE = "identity_mfa_totp_credential";
    private static final String COLUMN = "encrypted_secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant FIRST = Instant.parse("2026-10-22T09:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-10-22T09:10:00Z");

    private final ColumnEncryptionMasterKey masterKey = randomMasterKey();
    private final Argon2Pepper pepper =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void enrollingGeneratesTenDistinctRecoveryCodesShownOnce() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        seed(institutionId, accountId);

        Enrolled result = enroll(institutionId, accountId, FIRST);

        assertThat(result.recoveryCodes())
                .as("exactly ten distinct recovery codes, returned once in clear text")
                .hasSize(10)
                .doesNotHaveDuplicates();
        assertThat(result.replacedUnverified()).isFalse();
    }

    @Test
    void theEncryptedCredentialStartsAtCounterMinusOneAndDecryptsBackToTwentyBytes() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        seed(institutionId, accountId);

        enroll(institutionId, accountId, FIRST);

        transactionRunner().execute(contextOf(institutionId), () -> {
            var row = dsl.fetchOne("""
                    select last_accepted_counter, encrypted_secret
                    from identity_mfa_totp_credential
                    where institution_id = ? and account_id = ?
                    """, institutionId.value(), accountId.value());
            assertThat(row.get("last_accepted_counter", Long.class)).isEqualTo(-1L);
            String encryptedSecret = row.get("encrypted_secret", String.class);
            assertThat(encryptedSecret).startsWith("v1:");
            assertThat(decrypt(institutionId, accountId, encryptedSecret)).hasSize(20);
            return null;
        });
    }

    /**
     * [I180]: without the secret in base32 no authenticator app can learn it, and the second factor
     * is enrolled yet impossible to activate. Asserting the length and alphabet alone would pass for
     * any 20 random bytes, so the assertion that carries the weight is the last one: the base32
     * handed to the caller must encode the very bytes the stored ciphertext decrypts to.
     */
    @Test
    void theReturnedBase32SecretIsTheSameSecretThatWasStoredEncrypted() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        seed(institutionId, accountId);

        Enrolled result = enroll(institutionId, accountId, FIRST);

        assertThat(result.secret().base32())
                .as("20 bytes is a multiple of five, so RFC 4648 needs no padding here")
                .hasSize(32)
                .matches("[A-Z2-7]{32}");
        assertThat(storedSecretBase32(institutionId, accountId))
                .as("the secret handed to the caller must be the one that was stored, not "
                        + "another one generated alongside it")
                .isEqualTo(result.secret().base32());
    }

    @Test
    void noSubsequentQueryCanRecoverTheCodesInClearText() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        seed(institutionId, accountId);

        Enrolled result = enroll(institutionId, accountId, FIRST);

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
    void theFirstEnrollmentAuditsExactlyOneEnrolledEventWithNoDetail() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        seed(institutionId, accountId);

        enroll(institutionId, accountId, FIRST);

        List<AuditRowSnapshot> enrolledRows = auditRowsFor(institutionId, accountId).stream()
                .filter(row -> row.action().equals("identity.mfa.enrolled"))
                .toList();
        assertThat(enrolledRows)
                .as("a single identity.mfa.enrolled event covers both the credential and the ten "
                        + "recovery codes, never one row per code")
                .hasSize(1);
        assertThat(enrolledRows.get(0).afterValue())
                .as("the first enrollment keeps the entry it always had")
                .isNull();
    }

    /**
     * [I181]: while the credential was never verified, enrolling again replaces the secret and retires
     * the ten codes handed out before, and the trail says so with the number of codes retired.
     */
    @Test
    void enrollingAgainBeforeTheFirstVerificationReplacesTheSecretAndRetiresThePreviousCodes() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        seed(institutionId, accountId);
        Enrolled first = enroll(institutionId, accountId, FIRST);

        Enrolled second = enroll(institutionId, accountId, SECOND);

        assertThat(second.replacedUnverified()).isTrue();
        assertThat(second.secret().base32()).isNotEqualTo(first.secret().base32());
        assertThat(storedSecretBase32(institutionId, accountId))
                .as("the stored secret is the new one").isEqualTo(second.secret().base32());
        assertThat(second.recoveryCodes()).hasSize(10).doesNotHaveDuplicates()
                .doesNotContainAnyElementsOf(first.recoveryCodes());
        assertThat(countRecoveryCodes(institutionId, accountId, true))
                .as("the ten previous codes carry their instant of retirement").isEqualTo(10L);
        assertThat(countRecoveryCodes(institutionId, accountId, false))
                .as("and the unused ones are only the new ten").isEqualTo(10L);
        assertThat(instantsOfRetirement(institutionId, accountId)).containsOnly(SECOND);
        assertThat(isVerified(institutionId, accountId)).isFalse();

        List<AuditRowSnapshot> enrolled = auditRowsFor(institutionId, accountId).stream()
                .filter(row -> row.action().equals("identity.mfa.enrolled"))
                .toList();
        assertThat(enrolled).hasSize(2);
        assertThat(enrolled.stream().filter(row -> row.afterValue() != null).toList())
                .singleElement()
                .satisfies(row -> {
                    var detail = JSON.readTree(row.afterValue());
                    assertThat(detail.get("replacedUnverified").asBoolean()).isTrue();
                    assertThat(detail.get("invalidatedRecoveryCodes").asInt()).isEqualTo(10);
                    assertThat(detail.size()).isEqualTo(2);
                });
    }

    /**
     * [I182]: a verified credential is never replaced. The refusal writes nothing but its own entry,
     * not even a different secret or a new batch of codes.
     */
    @Test
    void enrollingAVerifiedCredentialIsRefusedAndWritesOnlyTheRejectionEntry() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        seed(institutionId, accountId);
        enroll(institutionId, accountId, FIRST);
        String secretBefore = storedSecretBase32(institutionId, accountId);
        verify(institutionId, accountId);
        long auditBefore = auditRowsFor(institutionId, accountId).size();

        EnrollTotpSecondFactorResult result = enrollAny(institutionId, accountId, SECOND);

        assertThat(result).isInstanceOf(AlreadyEnrolled.class);
        assertThat(storedSecretBase32(institutionId, accountId)).isEqualTo(secretBefore);
        assertThat(countRecoveryCodes(institutionId, accountId, false))
                .as("the ten codes of the verified credential stay usable").isEqualTo(10L);
        assertThat(countRecoveryCodes(institutionId, accountId, true)).isZero();
        assertThat(isVerified(institutionId, accountId)).isTrue();
        List<AuditRowSnapshot> after = auditRowsFor(institutionId, accountId);
        assertThat(after).hasSize((int) auditBefore + 1);
        AuditRowSnapshot rejection = after.stream()
                .filter(row -> row.action().equals("identity.mfa.enrollment_rejected"))
                .findFirst().orElseThrow();
        assertThat(rejection.outcome()).isEqualTo("denied");
        assertThat(JSON.readTree(rejection.afterValue()).get("reason").asString())
                .isEqualTo("already_enrolled");
        assertThat(after).extracting(AuditRowSnapshot::action)
                .filteredOn(action -> action.equals("identity.mfa.enrolled")).hasSize(1);
    }

    @Test
    void anAccountThatDoesNotExistCannotEnrollAndLeavesNothingBehind() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId ghost = new StaffAccountId(UUID.randomUUID());

        assertThatThrownBy(() -> enrollAny(institutionId, ghost, FIRST))
                .isInstanceOf(IllegalStateException.class);

        assertThat(transactionRunner().execute(contextOf(institutionId), () -> dsl.fetchOne(
                "select count(*) as n from identity_mfa_totp_credential").get("n", Long.class))).isZero();
    }

    private void seed(InstitutionId institutionId, StaffAccountId accountId) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            return null;
        });
    }

    private Enrolled enroll(InstitutionId institutionId, StaffAccountId accountId, Instant at) {
        return (Enrolled) enrollAny(institutionId, accountId, at);
    }

    private EnrollTotpSecondFactorResult enrollAny(InstitutionId institutionId,
            StaffAccountId accountId, Instant at) {
        EnrollTotpSecondFactor useCase = new EnrollTotpSecondFactor(transactionRunner(),
                new JooqStaffAccountRepository(dsl), new JooqTotpCredentialRepository(dsl),
                new JooqRecoveryCodeRepository(dsl),
                new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), pepper),
                encryptionService(), new JooqAuditLogWriter(dsl), Clock.fixed(at, ZoneOffset.UTC));
        return useCase.execute(contextOf(institutionId), accountId);
    }

    /** Verifies the credential the way a first accepted TOTP code does: the counter becomes non-negative. */
    private void verify(InstitutionId institutionId, StaffAccountId accountId) {
        transactionRunner().execute(contextOf(institutionId), () -> new JooqTotpCredentialRepository(dsl)
                .acceptCounter(institutionId, accountId, 58_204L));
    }

    private boolean isVerified(InstitutionId institutionId, StaffAccountId accountId) {
        return transactionRunner().execute(contextOf(institutionId),
                () -> new JooqTotpCredentialRepository(dsl).findByAccountId(institutionId, accountId))
                .orElseThrow().isVerified();
    }

    private String storedSecretBase32(InstitutionId institutionId, StaffAccountId accountId) {
        return transactionRunner().execute(contextOf(institutionId), () -> {
            String encryptedSecret = dsl.fetchOne("""
                    select encrypted_secret from identity_mfa_totp_credential
                    where institution_id = ? and account_id = ?
                    """, institutionId.value(), accountId.value())
                    .get("encrypted_secret", String.class);
            return PlainTotpSecret.of(decrypt(institutionId, accountId, encryptedSecret)).base32();
        });
    }

    private byte[] decrypt(InstitutionId institutionId, StaffAccountId accountId,
            String encryptedSecret) {
        try {
            return encryptionService().decrypt(TABLE, COLUMN, institutionId,
                    institutionId.value() + ":" + accountId.value(), encryptedSecret);
        } catch (AeadIntegrityException e) {
            throw new IllegalStateException("stored TOTP secret failed AEAD verification", e);
        }
    }

    private long countRecoveryCodes(InstitutionId institutionId, StaffAccountId accountId,
            boolean used) {
        return transactionRunner().execute(contextOf(institutionId), () -> dsl.fetchOne("""
                select count(*) as n from identity_mfa_recovery_code
                where institution_id = ? and account_id = ? and (used_at is not null) = ?
                """, institutionId.value(), accountId.value(), used).get("n", Long.class));
    }

    private List<Instant> instantsOfRetirement(InstitutionId institutionId, StaffAccountId accountId) {
        return transactionRunner().execute(contextOf(institutionId), () -> dsl.fetch("""
                select used_at from identity_mfa_recovery_code
                where institution_id = ? and account_id = ? and used_at is not null
                """, institutionId.value(), accountId.value()).stream()
                .map(row -> row.get("used_at", java.time.OffsetDateTime.class).toInstant())
                .toList());
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
