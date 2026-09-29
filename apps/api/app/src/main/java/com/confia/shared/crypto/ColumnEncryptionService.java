package com.confia.shared.crypto;

import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.EncryptedColumnValue;
import com.confia.kernel.InstitutionId;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Orchestrates column-level encryption end to end: resolves the right data-encryption key,
 * delegates the actual AES-256-GCM work to {@link AesGcmCipher} (never re-implementing it), and
 * formats/parses the stored value with {@link EncryptedColumnValue} (column-encryption-and-mfa-totp
 * design.md, decision 2, point 2; decision 5).
 *
 * <p>{@code final}, concrete, no port and no second implementation: there is exactly one
 * algorithm this change ships (AES-256-GCM, ADR-0023) and no test needs to substitute this
 * orchestration itself with a test double — only {@link DataEncryptionKeyRepository} needs one,
 * the same reasoning {@code com.confia.shared.security.TransactionRunner} already applies to
 * itself.
 */
public final class ColumnEncryptionService {

    private static final int TAG_LENGTH_BYTES = AesGcmCipher.TAG_LENGTH_BITS / 8;

    private final DataEncryptionKeyRepository keys;
    private final AesGcmCipher cipher;
    private final SecureRandom random;

    public ColumnEncryptionService(DataEncryptionKeyRepository keys, AesGcmCipher cipher) {
        this(keys, cipher, new SecureRandom());
    }

    ColumnEncryptionService(DataEncryptionKeyRepository keys, AesGcmCipher cipher,
            SecureRandom random) {
        this.keys = keys;
        this.cipher = cipher;
        this.random = random;
    }

    /**
     * Encrypts {@code plaintext} under the institution's currently active data-encryption key
     * (creating it the first time, design.md decision 4) and returns the formatted five-part
     * stored value ({@code v1:<id_dek>:<iv_b64>:<ciphertext_b64>:<tag_b64>}).
     *
     * @param rowId the row's own identifier for the additional authenticated data (design.md,
     *     decision 5): for a table whose primary key is not a single column of its own — {@code
     *     identity_mfa_totp_credential}, keyed by {@code (institution_id, account_id)} — this is
     *     {@code institutionId + ":" + accountId}, not just {@code accountId}
     */
    public String encryptForNewValue(String table, String column, InstitutionId institutionId,
            String rowId, byte[] plaintext) {
        DataEncryptionKeyMaterial dek = keys.findActiveOrCreate(institutionId);
        byte[] iv = new byte[AesGcmCipher.IV_LENGTH_BYTES];
        random.nextBytes(iv);
        byte[] aad = aadOf(table, column, institutionId, rowId);
        byte[] ciphertextWithTag = cipher.encrypt(dek.rawKeyBytes(), iv, aad, plaintext);
        int splitIndex = ciphertextWithTag.length - TAG_LENGTH_BYTES;
        byte[] ciphertext = Arrays.copyOfRange(ciphertextWithTag, 0, splitIndex);
        byte[] tag = Arrays.copyOfRange(ciphertextWithTag, splitIndex, ciphertextWithTag.length);
        return new EncryptedColumnValue(dek.id().value().toString(), iv, ciphertext, tag).format();
    }

    /**
     * Decrypts {@code storedValue}, resolving the exact data-encryption key it names (active or
     * retired, design.md decision 5, "Cómo se elige la DEK") — never the institution's currently
     * active key, which is why a retired DEK's own encrypted values keep decrypting correctly
     * after rotation.
     *
     * @throws AeadIntegrityException if the additional authenticated data recomputed from {@code
     *     table}, {@code column}, {@code institutionId} and {@code rowId} does not match what
     *     {@code storedValue} was encrypted with — the mechanism that fails decryption when a
     *     stored value is copied from one row to another
     */
    public byte[] decrypt(String table, String column, InstitutionId institutionId, String rowId,
            String storedValue) throws AeadIntegrityException {
        EncryptedColumnValue parsed = EncryptedColumnValue.parse(storedValue);
        DataEncryptionKeyMaterial dek = keys.findById(institutionId,
                new DataEncryptionKeyId(UUID.fromString(parsed.dekId())));
        byte[] aad = aadOf(table, column, institutionId, rowId);
        byte[] ciphertextWithTag = concat(parsed.ciphertext(), parsed.tag());
        return cipher.decrypt(dek.rawKeyBytes(), parsed.iv(), aad, ciphertextWithTag);
    }

    private static byte[] aadOf(String table, String column, InstitutionId institutionId,
            String rowId) {
        return (table + "|" + column + "|" + institutionId.value() + ":" + rowId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
