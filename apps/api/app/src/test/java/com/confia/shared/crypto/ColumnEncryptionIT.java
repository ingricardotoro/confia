package com.confia.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqDataEncryptionKeyRepository;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link ColumnEncryptionService} against a real PostgreSQL (column-encryption-and-mfa-totp
 * design.md decisions 2, 4 and 5; specs/identity/spec.md, requirement "Cifrado a nivel de
 * columna..."; specs/build-integrity/spec.md, requirement "Tablas nuevas de cifrado de
 * columna y MFA...").
 *
 * <p>Seeds {@code identity_mfa_totp_credential} directly by raw SQL (test-only, never a production
 * adapter — {@code JooqTotpCredentialRepository} does not exist until cut C2): this class exercises
 * only the encryption engine's own contract, not TOTP enrollment or verification.
 */
class ColumnEncryptionIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";
    private static final String TABLE = "identity_mfa_totp_credential";
    private static final String COLUMN = "encrypted_secret";

    @Test
    void theStoredValueStartsWithV1AndNeverContainsThePlaintextSecret() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        UUID accountId = UUID.randomUUID();
        String plaintextSecret = "correct horse battery staple, twenty bytes plus";
        ColumnEncryptionService service = serviceFor(randomMasterKey());

        String encrypted = transactionRunner().execute(contextOf(institutionId), () -> {
            String value = service.encryptForNewValue(TABLE, COLUMN, institutionId,
                    rowIdOf(institutionId, accountId),
                    plaintextSecret.getBytes(StandardCharsets.UTF_8));
            seedAccountAndCredential(institutionId, accountId, value);
            return value;
        });

        String selected = transactionRunner().execute(contextOf(institutionId),
                () -> dsl.fetchOne(
                                "select encrypted_secret from identity_mfa_totp_credential "
                                        + "where institution_id = ? and account_id = ?",
                                institutionId.value(), accountId)
                        .get("encrypted_secret", String.class));

        assertThat(selected).isEqualTo(encrypted);
        assertThat(selected).startsWith("v1:");
        assertThat(selected).doesNotContain(plaintextSecret);
    }

    /**
     * The escenario publicado "Un valor cifrado con los datos autenticados de una fila falla al
     * descifrarse con los de otra fila" (specs/identity/spec.md): substituting the row identifier
     * (the {@code accountId} half of {@code institutionId:accountId}, design.md decision 5) must
     * fail with exactly {@link AeadIntegrityException}.
     */
    @Test
    void decryptingWithAnotherAccountsRowIdentifierFailsAuthentication() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        UUID accountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        String plaintextSecret = "another twenty-byte-plus TOTP secret!!";
        ColumnEncryptionService service = serviceFor(randomMasterKey());

        String encrypted = transactionRunner().execute(contextOf(institutionId),
                () -> service.encryptForNewValue(TABLE, COLUMN, institutionId,
                        rowIdOf(institutionId, accountId),
                        plaintextSecret.getBytes(StandardCharsets.UTF_8)));

        byte[] roundTripped = transactionRunner().execute(contextOf(institutionId), () -> decryptOrFail(
                service, institutionId, rowIdOf(institutionId, accountId), encrypted));
        assertThat(new String(roundTripped, StandardCharsets.UTF_8)).isEqualTo(plaintextSecret);

        assertThatThrownBy(() -> transactionRunner().execute(contextOf(institutionId),
                () -> decryptOrFail(service, institutionId, rowIdOf(institutionId, otherAccountId),
                        encrypted)))
                .as("a different accountId in the row identifier must change the additional "
                        + "authenticated data GCM verifies, never silently decrypt")
                .hasCauseInstanceOf(AeadIntegrityException.class);
    }

    /**
     * Two concurrent first enrollments of the same institution, synchronized with a {@link
     * CyclicBarrier} right after each opens its own transaction and before either calls
     * {@code DataEncryptionKeyRepository.findActiveOrCreate} — the same placement {@code
     * LoginBackoffConcurrencyIT} already established (design.md decision 4; sonda S5,
     * apply-progress.md task 1.1).
     */
    @Test
    void twoConcurrentFirstEnrollmentsEndWithExactlyOneActiveDataEncryptionKey() throws Exception {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        ColumnEncryptionMasterKey masterKey = randomMasterKey();
        CyclicBarrier bothTransactionsOpenBeforeEitherClaims = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> futureA = executor.submit(
                    claimTask(institutionId, masterKey, bothTransactionsOpenBeforeEitherClaims));
            Future<UUID> futureB = executor.submit(
                    claimTask(institutionId, masterKey, bothTransactionsOpenBeforeEitherClaims));

            UUID dekIdSeenByA = futureA.get(30, TimeUnit.SECONDS);
            UUID dekIdSeenByB = futureB.get(30, TimeUnit.SECONDS);

            assertThat(dekIdSeenByA)
                    .as("both concurrent callers must resolve to the very same winning DEK")
                    .isEqualTo(dekIdSeenByB);
        } finally {
            executor.shutdownNow();
        }

        long activeCount = transactionRunner().execute(contextOf(institutionId),
                () -> dsl.fetchOne(
                                "select count(*) as c from shared_data_encryption_key "
                                        + "where institution_id = ? and status = 'active'",
                                institutionId.value())
                        .get("c", Number.class).longValue());
        assertThat(activeCount).isEqualTo(1L);
    }

    private Callable<UUID> claimTask(InstitutionId institutionId, ColumnEncryptionMasterKey masterKey,
            CyclicBarrier barrier) {
        TransactionRunner independentRunner = new TransactionRunner(transactionManager(), dataSource());
        JooqDataEncryptionKeyRepository repository =
                new JooqDataEncryptionKeyRepository(dsl, new AesGcmCipher(), masterKey);
        SecurityContext context = contextOf(institutionId);
        return () -> independentRunner.execute(context, () -> {
            awaitUninterruptibly(barrier);
            return repository.findActiveOrCreate(institutionId).id().value();
        });
    }

    private static void awaitUninterruptibly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("both sides of the race must reach the barrier", e);
        }
    }

    /** Unwraps the checked {@link AeadIntegrityException} into an unchecked one for callers inside
     * a {@link java.util.function.Supplier}, which cannot declare a checked throws clause. Any
     * caller expecting the failure asserts on {@code getCause()}. */
    private static byte[] decryptOrFail(ColumnEncryptionService service, InstitutionId institutionId,
            String rowId, String storedValue) {
        try {
            return service.decrypt(TABLE, COLUMN, institutionId, rowId, storedValue);
        } catch (AeadIntegrityException e) {
            throw new IllegalStateException(e);
        }
    }

    private ColumnEncryptionService serviceFor(ColumnEncryptionMasterKey masterKey) {
        return new ColumnEncryptionService(
                new JooqDataEncryptionKeyRepository(dsl, new AesGcmCipher(), masterKey),
                new AesGcmCipher());
    }

    private static ColumnEncryptionMasterKey randomMasterKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return ColumnEncryptionMasterKey.fromBase64(Base64.getEncoder().encodeToString(key));
    }

    private void seedAccountAndCredential(InstitutionId institutionId, UUID accountId,
            String encryptedSecret) {
        dsl.execute("""
                insert into identity_staff_account
                    (institution_id, id, email, password_hash, mfa_required)
                values (?, ?, ?, ?, true)
                """, institutionId.value(), accountId, "mfa." + accountId + "@colegio.edu.hn",
                PLACEHOLDER_PASSWORD_HASH);
        dsl.execute("""
                insert into identity_mfa_totp_credential (institution_id, account_id, encrypted_secret)
                values (?, ?, ?)
                """, institutionId.value(), accountId, encryptedSecret);
    }

    /** {@code institutionId:accountId} (design.md, decision 5): the table's row has no single-column
     * identifier of its own, so the row component of the AAD encodes the composite primary key. */
    private static String rowIdOf(InstitutionId institutionId, UUID accountId) {
        return institutionId.value() + ":" + accountId;
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
