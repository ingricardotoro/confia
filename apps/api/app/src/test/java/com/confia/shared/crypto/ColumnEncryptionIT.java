package com.confia.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.EncryptedColumnValue;
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
     * The same property as the test above, one level up: for the <b>envelope</b> that protects every
     * DEK, not for a business column value. The pre-merge security audit of this slice found the
     * wrapped key's additional authenticated data was a single fixed label, shared by every row of
     * every institution, so this transplant used to succeed — the root of the key hierarchy lacked
     * the barrier its own leaves already had.
     *
     * <p>The row is planted by raw SQL on purpose: no production path can move a {@code wrapped_key}
     * between institutions, and the point is precisely what happens when something outside those
     * paths does.
     */
    @Test
    void aWrappedKeyTransplantedIntoAnotherInstitutionsRowFailsToUnwrap() {
        ColumnEncryptionMasterKey masterKey = randomMasterKey();
        InstitutionId institutionA = new InstitutionId(UUID.randomUUID());
        InstitutionId institutionB = new InstitutionId(UUID.randomUUID());

        String wrappedKeyOfA = transactionRunner().execute(contextOf(institutionA), () -> {
            repositoryFor(masterKey).findActiveOrCreate(institutionA);
            return dsl.fetchOne("select wrapped_key as w from shared_data_encryption_key "
                            + "where institution_id = ?", institutionA.value())
                    .get("w", String.class);
        });

        UUID transplantedId = transactionRunner().execute(contextOf(institutionB), () -> {
            UUID id = UUID.randomUUID();
            dsl.execute("""
                    insert into shared_data_encryption_key
                        (institution_id, id, status, wrapped_key)
                    values (?, ?, 'active', ?)
                    """, institutionB.value(), id, wrappedKeyOfA);
            return id;
        });

        assertThatThrownBy(() -> transactionRunner().execute(contextOf(institutionB),
                () -> repositoryFor(masterKey)
                        .findById(institutionB, new DataEncryptionKeyId(transplantedId))))
                .as("institution B must not unwrap institution A's data encryption key, although "
                        + "the KEK is the same and the ciphertext is byte-identical: the additional "
                        + "authenticated data is the only barrier between the two envelopes")
                .isInstanceOf(IllegalStateException.class)
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

    /**
     * The behavioral half of the escenario publicado "Ninguna DEK retirada se recifra
     * automáticamente" (specs/identity/spec.md, requirement "Ausencia de ejecución real de la
     * rotación de la llave de datos..."): a value encrypted under a key that is later marked
     * {@code retired} keeps decrypting, because {@code retired} forbids encrypting new values with
     * that key, never reading old ones; and the next new value goes under a fresh active key. The
     * absence half — no scheduled job re-encrypts anything — is a static inventory in {@code
     * IdentityScopeExclusionInventoryTest}, which needs no container.
     *
     * <p>The key is retired by raw SQL because this change ships no retirement operation: rotation
     * is change 9's, and so is whatever will mark a key {@code retired} in production.
     */
    @Test
    void aValueEncryptedUnderARetiredKeyStillDecryptsAndNewValuesUseAFreshActiveKey() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        UUID accountId = UUID.randomUUID();
        byte[] plaintext = "a TOTP secret stored before rotation".getBytes(StandardCharsets.UTF_8);
        ColumnEncryptionService service = serviceFor(randomMasterKey());

        String storedUnderOldKey = transactionRunner().execute(contextOf(institutionId), () -> {
            String value = service.encryptForNewValue(TABLE, COLUMN, institutionId,
                    rowIdOf(institutionId, accountId), plaintext);
            seedAccountAndCredential(institutionId, accountId, value);
            return value;
        });
        String oldKeyId = EncryptedColumnValue.parse(storedUnderOldKey).dekId();

        int retired = transactionRunner().execute(contextOf(institutionId), () -> dsl.execute("""
                update shared_data_encryption_key set status = 'retired'
                where institution_id = ? and id = ?
                """, institutionId.value(), UUID.fromString(oldKeyId)));
        assertThat(retired).as("exactly the key the value names is retired").isEqualTo(1);

        String storedValueAfterRetirement = transactionRunner().execute(contextOf(institutionId),
                () -> dsl.fetchOne("""
                        select encrypted_secret from identity_mfa_totp_credential
                        where institution_id = ? and account_id = ?
                        """, institutionId.value(), accountId)
                        .get("encrypted_secret", String.class));
        byte[] decrypted = transactionRunner().execute(contextOf(institutionId),
                () -> decryptOrFail(service, institutionId, rowIdOf(institutionId, accountId),
                        storedValueAfterRetirement));
        String newValue = transactionRunner().execute(contextOf(institutionId),
                () -> service.encryptForNewValue(TABLE, COLUMN, institutionId,
                        rowIdOf(institutionId, UUID.randomUUID()), plaintext));

        assertThat(storedValueAfterRetirement)
                .as("nothing re-encrypted the stored value when its key was retired")
                .isEqualTo(storedUnderOldKey);
        assertThat(decrypted)
                .as("retired forbids encrypting with the key, never decrypting with it")
                .isEqualTo(plaintext);
        assertThat(EncryptedColumnValue.parse(newValue).dekId())
                .as("a new value is encrypted under a fresh active key, never the retired one")
                .isNotEqualTo(oldKeyId);
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
        return new ColumnEncryptionService(repositoryFor(masterKey), new AesGcmCipher());
    }

    private JooqDataEncryptionKeyRepository repositoryFor(ColumnEncryptionMasterKey masterKey) {
        return new JooqDataEncryptionKeyRepository(dsl, new AesGcmCipher(), masterKey);
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
