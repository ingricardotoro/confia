package com.confia.kernel;

import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM, byte to byte: no knowledge of what a data-encryption key is, which table a value
 * belongs to, or how the five-part stored format looks (design.md, decision 2, point 1; decision
 * 5). {@code javax.crypto} is part of the JDK, so this class adds no dependency beyond what {@code
 * enforce-kernel-purity} already allows (kernel {@code package-info.java}).
 *
 * <p><b>{@link #encrypt} returns the ciphertext with the authentication tag concatenated to its
 * end, exactly as {@code Cipher.doFinal(...)} for {@code "AES/GCM/NoPadding"} produces it</b> —
 * confirmed by sonda S1 (apply-progress.md, task 1.1) against a real {@code SunJCE} provider: the
 * output measures exactly {@code plaintext.length + 16} bytes, the 128-bit tag. This class performs
 * no manual splitting of that output; {@link #decrypt} accepts the same concatenated shape back,
 * because the JDK's {@code Cipher} already verifies and strips the tag internally on the decrypt
 * path. Splitting the concatenated bytes into separate {@code ciphertext}/{@code tag} fields for
 * the stored five-part format — and reassembling them before a later decrypt — is
 * {@code ColumnEncryptionService}'s job (design.md, decision 5), not this class's.
 */
public final class AesGcmCipher {

    public static final String ALGORITHM = "AES/GCM/NoPadding";
    public static final int KEY_LENGTH_BYTES = 32;
    public static final int IV_LENGTH_BYTES = 12;
    public static final int TAG_LENGTH_BITS = 128;

    /**
     * @return the ciphertext with the 128-bit authentication tag concatenated to its end
     * @throws IllegalArgumentException if {@code key} is not exactly {@value #KEY_LENGTH_BYTES}
     *     bytes or {@code iv} is not exactly {@value #IV_LENGTH_BYTES} bytes
     */
    public byte[] encrypt(byte[] key, byte[] iv, byte[] aad, byte[] plaintext) {
        try {
            Cipher cipher = newCipher(Cipher.ENCRYPT_MODE, key, iv);
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            // Every checked cause of Cipher.init/updateAAD/doFinal on the encrypt path
            // (InvalidKeyException, InvalidAlgorithmParameterException) is a programming error
            // here, never a business condition: key and IV length are already validated above,
            // and the algorithm string is a compile-time constant.
            throw new IllegalStateException("AES-GCM encryption failed unexpectedly", e);
        }
    }

    /**
     * @param ciphertextWithTag the ciphertext with the 128-bit authentication tag concatenated to
     *     its end, exactly as {@link #encrypt} returns it
     * @throws AeadIntegrityException if the tag does not match {@code key}, {@code iv}, {@code
     *     aad} and {@code ciphertextWithTag}
     * @throws IllegalArgumentException if {@code key} is not exactly {@value #KEY_LENGTH_BYTES}
     *     bytes or {@code iv} is not exactly {@value #IV_LENGTH_BYTES} bytes
     */
    public byte[] decrypt(byte[] key, byte[] iv, byte[] aad, byte[] ciphertextWithTag)
            throws AeadIntegrityException {
        try {
            Cipher cipher = newCipher(Cipher.DECRYPT_MODE, key, iv);
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertextWithTag);
        } catch (AEADBadTagException e) {
            throw new AeadIntegrityException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed unexpectedly", e);
        }
    }

    private static Cipher newCipher(int mode, byte[] key, byte[] iv)
            throws GeneralSecurityException {
        requireLength(key, KEY_LENGTH_BYTES, "key");
        requireLength(iv, IV_LENGTH_BYTES, "iv");
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return cipher;
    }

    private static void requireLength(byte[] value, int expectedLength, String name) {
        Objects.requireNonNull(value, name);
        if (value.length != expectedLength) {
            throw new IllegalArgumentException(
                    name + " must be exactly " + expectedLength + " bytes, was " + value.length
                            + " bytes");
        }
    }
}
