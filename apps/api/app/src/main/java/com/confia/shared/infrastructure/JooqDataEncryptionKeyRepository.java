package com.confia.shared.infrastructure;

import static confia.generated.jooq.tables.SharedDataEncryptionKey.SHARED_DATA_ENCRYPTION_KEY;

import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.crypto.ColumnEncryptionMasterKey;
import com.confia.shared.crypto.DataEncryptionKeyId;
import com.confia.shared.crypto.DataEncryptionKeyMaterial;
import com.confia.shared.crypto.DataEncryptionKeyRepository;
import confia.generated.jooq.tables.records.SharedDataEncryptionKeyRecord;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The single jOOQ adapter of {@link DataEncryptionKeyRepository}
 * (column-encryption-and-mfa-totp design.md, decision 2; ADR-0015 rule 4, R1: jOOQ confined to
 * {@code infrastructure}; rule 3, R2: {@link
 * confia.generated.jooq.tables.SharedDataEncryptionKey}'s generated table type carries this
 * module's own {@code Shared} prefix, confirmed by sonda S2, apply-progress.md task 1.2).
 * {@code final}, with an explicit constructor and no Spring annotation, the same pattern
 * {@link JooqAuditLogWriter} and {@link JooqIdempotencyRecordStore} already established: no
 * bootstrap process registers this as a bean yet.
 *
 * <p><b>Directly in {@code com.confia.shared.infrastructure}, not in a nested {@code
 * shared.crypto.infrastructure} package</b> (design.md, decision 2). {@code
 * TableOwnershipByModuleTest} derives the required generated-table-type prefix from the package
 * segment immediately before the first layer segment (rule R2). A nested {@code
 * shared.crypto.infrastructure} package would make that segment {@code crypto}, requiring a
 * {@code Crypto} prefix — contradicting D3 of the proposal, which already fixed the table's
 * prefix at {@code shared_}. This class lives here for exactly the same reason
 * {@link JooqAuditLogWriter} and {@link JooqIdempotencyRecordStore} do.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): every method assumes it
 * runs inside the transaction the single transactional component of {@code shared/security}
 * already opened.
 *
 * <p><b>{@link #findActiveOrCreate} is decision 4's exact lazy-creation algorithm</b>, confirmed
 * safe under concurrency by sonda S5 (apply-progress.md, task 1.1): a {@code SELECT} for the
 * active row first; if absent, generate a fresh candidate key, wrap it under the KEK, and {@code
 * INSERT ... ON CONFLICT (institution_id) WHERE status = 'active' DO NOTHING} against the exact
 * partial index {@code V6} declares; then a final {@code SELECT} returns whichever row actually
 * won — its own insert, if there was no collision, or the concurrent session's that got there
 * first. No {@code SELECT ... FOR UPDATE} is needed before it: the single statement already
 * resolves the race.
 */
public final class JooqDataEncryptionKeyRepository implements DataEncryptionKeyRepository {

    /** Never part of the stored {@code wrapped_key} format (design.md decision 3, point 3): this
     * table's own {@code (institution_id, id)} row already ties the wrapping to one institution
     * and one key, so the additional authenticated data need only be a fixed, stable label. */
    private static final byte[] WRAPPED_KEY_AAD =
            "shared_data_encryption_key.wrapped_key".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static final int TAG_LENGTH_BYTES = AesGcmCipher.TAG_LENGTH_BITS / 8;

    private final DSLContext dsl;
    private final AesGcmCipher cipher;
    private final ColumnEncryptionMasterKey masterKey;
    private final SecureRandom random;

    public JooqDataEncryptionKeyRepository(DSLContext dsl, AesGcmCipher cipher,
            ColumnEncryptionMasterKey masterKey) {
        this(dsl, cipher, masterKey, new SecureRandom());
    }

    JooqDataEncryptionKeyRepository(DSLContext dsl, AesGcmCipher cipher,
            ColumnEncryptionMasterKey masterKey, SecureRandom random) {
        this.dsl = dsl;
        this.cipher = cipher;
        this.masterKey = masterKey;
        this.random = random;
    }

    @Override
    public DataEncryptionKeyMaterial findActiveOrCreate(InstitutionId institutionId) {
        Optional<SharedDataEncryptionKeyRecord> active = selectActive(institutionId);
        if (active.isPresent()) {
            return toMaterial(active.get());
        }

        byte[] rawKey = new byte[DataEncryptionKeyMaterial.LENGTH_BYTES];
        random.nextBytes(rawKey);
        UUID candidateId = UUID.randomUUID();

        dsl.insertInto(SHARED_DATA_ENCRYPTION_KEY)
                .set(SHARED_DATA_ENCRYPTION_KEY.INSTITUTION_ID, institutionId.value())
                .set(SHARED_DATA_ENCRYPTION_KEY.ID, candidateId)
                .set(SHARED_DATA_ENCRYPTION_KEY.STATUS, "active")
                .set(SHARED_DATA_ENCRYPTION_KEY.WRAPPED_KEY, wrap(rawKey))
                .onConflict(SHARED_DATA_ENCRYPTION_KEY.INSTITUTION_ID)
                .where(SHARED_DATA_ENCRYPTION_KEY.STATUS.eq("active"))
                .doNothing()
                .execute();

        return selectActive(institutionId).map(this::toMaterial)
                .orElseThrow(() -> new IllegalStateException(
                        "no active data encryption key found for the institution right after "
                                + "an idempotent insert; this should be unreachable"));
    }

    @Override
    public DataEncryptionKeyMaterial findById(InstitutionId institutionId, DataEncryptionKeyId id) {
        return dsl.selectFrom(SHARED_DATA_ENCRYPTION_KEY)
                .where(SHARED_DATA_ENCRYPTION_KEY.INSTITUTION_ID.eq(institutionId.value()))
                .and(SHARED_DATA_ENCRYPTION_KEY.ID.eq(id.value()))
                .fetchOptional(this::toMaterial)
                .orElseThrow(() -> new IllegalStateException(
                        "no data encryption key " + id.value() + " found for its institution"));
    }

    private Optional<SharedDataEncryptionKeyRecord> selectActive(InstitutionId institutionId) {
        return dsl.selectFrom(SHARED_DATA_ENCRYPTION_KEY)
                .where(SHARED_DATA_ENCRYPTION_KEY.INSTITUTION_ID.eq(institutionId.value()))
                .and(SHARED_DATA_ENCRYPTION_KEY.STATUS.eq("active"))
                .fetchOptional();
    }

    private DataEncryptionKeyMaterial toMaterial(SharedDataEncryptionKeyRecord record) {
        return new DataEncryptionKeyMaterial(new DataEncryptionKeyId(record.getId()),
                unwrap(record.getWrappedKey()));
    }

    /** {@code <iv_b64>:<ciphertext_b64>:<tag_b64>}, exactly the three-part shape {@code V6}'s own
     * {@code shared_data_encryption_key_wrapped_chk} enforces (design.md, decision 3, point 3) —
     * never the five-part {@code v1:<id_dek>:...} column format: there is only one KEK configured
     * in this change, so a wrapped key needs no key identifier of its own. */
    private String wrap(byte[] rawKey) {
        byte[] iv = new byte[AesGcmCipher.IV_LENGTH_BYTES];
        random.nextBytes(iv);
        byte[] ciphertextWithTag = cipher.encrypt(masterKey.value(), iv, WRAPPED_KEY_AAD, rawKey);
        int splitIndex = ciphertextWithTag.length - TAG_LENGTH_BYTES;
        Base64.Encoder encoder = Base64.getEncoder();
        return encoder.encodeToString(iv) + ":"
                + encoder.encodeToString(Arrays.copyOfRange(ciphertextWithTag, 0, splitIndex)) + ":"
                + encoder.encodeToString(
                        Arrays.copyOfRange(ciphertextWithTag, splitIndex, ciphertextWithTag.length));
    }

    private byte[] unwrap(String wrappedKey) {
        String[] parts = wrappedKey.split(":", -1);
        if (parts.length != 3) {
            // Never logs wrappedKey itself (CLAUDE.md regla 11): it is KEK-encrypted, not
            // plaintext, but the format failure itself is enough detail without echoing it.
            throw new IllegalStateException(
                    "a wrapped data encryption key must have exactly three base64 parts "
                            + "separated by ':'");
        }
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] iv = decoder.decode(parts[0]);
        byte[] ciphertext = decoder.decode(parts[1]);
        byte[] tag = decoder.decode(parts[2]);
        byte[] ciphertextWithTag = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, ciphertextWithTag, 0, ciphertext.length);
        System.arraycopy(tag, 0, ciphertextWithTag, ciphertext.length, tag.length);
        try {
            return cipher.decrypt(masterKey.value(), iv, WRAPPED_KEY_AAD, ciphertextWithTag);
        } catch (AeadIntegrityException e) {
            throw new IllegalStateException("failed to unwrap a stored data encryption key", e);
        }
    }
}
