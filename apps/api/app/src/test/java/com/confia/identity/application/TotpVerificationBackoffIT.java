package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.TotpCode;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.identity.infrastructure.JooqTotpVerificationBackoffStore;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rate-limit escenario publicado of specs/identity/spec.md ("El sexto intento de verificación
 * TOTP en la misma ventana de 15 minutos activa el retroceso", column-encryption-and-mfa-totp
 * design.md, decision 8): five failed verifications, then a sixth that pays the exponential delay
 * before its own outcome is decided, with the backoff cycle audited with its duration.
 *
 * <p><b>Never sleeps</b>: every attempt uses its own {@link Clock#fixed}, exactly {@code
 * AuthenticateWithPasswordIT}'s own precedent for the login backoff.
 */
class TotpVerificationBackoffIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final TotpCode WRONG_CODE = new TotpCode("000000");

    private final ColumnEncryptionMasterKey masterKey = randomMasterKey();

    @Test
    void theSixthFailedAttemptInTheSameWindowAppliesBackoffBeforeItsOwnOutcome() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        seedCredential(institutionId, accountId);

        verify(institutionId, accountId, WRONG_CODE, "2026-10-05T09:00:00Z");
        verify(institutionId, accountId, WRONG_CODE, "2026-10-05T09:02:00Z");
        verify(institutionId, accountId, WRONG_CODE, "2026-10-05T09:04:00Z");
        verify(institutionId, accountId, WRONG_CODE, "2026-10-05T09:06:00Z");
        verify(institutionId, accountId, WRONG_CODE, "2026-10-05T09:08:00Z");
        VerifyTotpCodeDecision sixth =
                verify(institutionId, accountId, WRONG_CODE, "2026-10-05T09:12:00Z");

        assertThat(sixth.requiredDelay())
                .as("ordinal 6, FIRST_DELAYED_ATTEMPT=3: 2^(6-3) = 8 seconds")
                .isEqualTo(Duration.ofSeconds(8));
        assertThat(sixth.accepted()).isFalse();

        List<AuditRowSnapshot> auditRows = auditRowsFor(institutionId, accountId);
        List<AuditRowSnapshot> backoffCycleRows = auditRows.stream()
                .filter(row -> row.action().equals("identity.mfa.totp_verification.backoff_applied"))
                .toList();
        assertThat(backoffCycleRows)
                .as("ordinals 3 through 6 all have a positive delay, so all four write their own "
                        + "backoff_applied row — the sixth call's own row is the last one, "
                        + "strictly ordered by id ascending (AuditLogReader's own contract)")
                .hasSize(4);
        JsonNode afterValue = JSON_MAPPER.readTree(backoffCycleRows.getLast().afterValue());
        assertThat(afterValue.get("delaySeconds").asInt()).isEqualTo(8);
        assertThat(afterValue.get("consecutiveFailures").asInt()).isEqualTo(6);
    }

    private VerifyTotpCodeDecision verify(InstitutionId institutionId, StaffAccountId accountId,
            TotpCode code, String instant) {
        VerifyTotpCode useCase = new VerifyTotpCode(transactionRunner(),
                new JooqTotpCredentialRepository(dsl), new JooqTotpVerificationBackoffStore(dsl),
                encryptionService(), new JooqAuditLogWriter(dsl),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        return useCase.execute(contextOf(institutionId), accountId, code);
    }

    private void seedCredential(InstitutionId institutionId, StaffAccountId accountId) {
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

    private static String rowIdOf(InstitutionId institutionId, StaffAccountId accountId) {
        return institutionId.value() + ":" + accountId.value();
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
