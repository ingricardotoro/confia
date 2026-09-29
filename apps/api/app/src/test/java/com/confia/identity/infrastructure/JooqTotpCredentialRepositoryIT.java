package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.TotpCredentialRepository;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.TotpCredential;
import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.crypto.ColumnEncryptionMasterKey;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.infrastructure.JooqDataEncryptionKeyRepository;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link JooqTotpCredentialRepository} against a real PostgreSQL
 * (column-encryption-and-mfa-totp design.md, decision 2 point 4, decision 5, decision 7;
 * specs/identity/spec.md, requirement "Inscripción y verificación del segundo factor TOTP...").
 * Seeds the credential's {@code encrypted_secret} through {@link ColumnEncryptionService} (from
 * cut C1), exactly as {@link com.confia.shared.crypto.ColumnEncryptionIT} does for the same table.
 */
class JooqTotpCredentialRepositoryIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";
    private static final String TABLE = "identity_mfa_totp_credential";
    private static final String COLUMN = "encrypted_secret";

    @Test
    void theEncryptedSecretRoundTripsBackToItsOriginalPlaintext() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        String plaintextSecret = "correct horse battery staple, twenty bytes plus";
        ColumnEncryptionService encryption = encryptionServiceFor(randomMasterKey());
        TotpCredentialRepository repository = repositoryFor();

        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            String encrypted = encryption.encryptForNewValue(TABLE, COLUMN, institutionId,
                    rowIdOf(institutionId, accountId), plaintextSecret.getBytes(StandardCharsets.UTF_8));
            repository.insert(institutionId, accountId, encrypted);
            return null;
        });

        Optional<TotpCredential> found = transactionRunner().execute(contextOf(institutionId),
                () -> repository.findByAccountId(institutionId, accountId));

        assertThat(found).isPresent();
        assertThat(found.get().lastAcceptedCounter()).isEqualTo(-1L);
        byte[] decrypted = transactionRunner().execute(contextOf(institutionId),
                () -> decryptOrFail(encryption, institutionId, accountId,
                        found.get().encryptedSecret()));
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo(plaintextSecret);
    }

    /**
     * design.md decision 7, "Persistencia del contador aceptado: UPDATE condicional, no SELECT más
     * UPDATE": a candidate counter no greater than the row's own already-advanced
     * {@code last_accepted_counter} must update zero rows — the same defeat signal a concurrent,
     * already-winning verification would have left behind.
     */
    @Test
    void theConditionalCounterUpdateAffectsZeroRowsWhenTheCounterAlreadyAdvanced() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        TotpCredentialRepository repository = repositoryFor();

        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            repository.insert(institutionId, accountId, "v1:" + UUID.randomUUID() + ":aXY=:Y3Q=:dGFn");
            return null;
        });

        boolean advancedToTen = transactionRunner().execute(contextOf(institutionId),
                () -> repository.acceptCounter(institutionId, accountId, 10L));
        assertThat(advancedToTen).isTrue();

        boolean rejectedStaleCandidate = transactionRunner().execute(contextOf(institutionId),
                () -> repository.acceptCounter(institutionId, accountId, 9L));
        assertThat(rejectedStaleCandidate)
                .as("a candidate counter that is not greater than the already-accepted one must "
                        + "update zero rows, even though it is only being compared, never re-read")
                .isFalse();

        Optional<TotpCredential> found = transactionRunner().execute(contextOf(institutionId),
                () -> repository.findByAccountId(institutionId, accountId));
        assertThat(found).isPresent();
        assertThat(found.get().lastAcceptedCounter()).isEqualTo(10L);
    }

    private TotpCredentialRepository repositoryFor() {
        return new JooqTotpCredentialRepository(dsl);
    }

    private ColumnEncryptionService encryptionServiceFor(ColumnEncryptionMasterKey masterKey) {
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

    private static byte[] decryptOrFail(ColumnEncryptionService service, InstitutionId institutionId,
            StaffAccountId accountId, String storedValue) {
        try {
            return service.decrypt(TABLE, COLUMN, institutionId, rowIdOf(institutionId, accountId),
                    storedValue);
        } catch (AeadIntegrityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
