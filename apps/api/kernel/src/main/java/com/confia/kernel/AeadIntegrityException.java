package com.confia.kernel;

/**
 * Thrown by {@link AesGcmCipher#decrypt} when AES-GCM authentication fails: the key, IV,
 * additional authenticated data or ciphertext presented do not reproduce the original 128-bit
 * authentication tag (design.md, decision 5). Wraps the JDK's own {@code
 * javax.crypto.AEADBadTagException} — confirmed as the exact exception type a mismatched
 * additional authenticated data raises by sonda S1 (apply-progress.md, task 1.1) — so a caller
 * never needs a direct dependency on {@code javax.crypto}'s exception hierarchy to catch this
 * specific failure by name.
 *
 * <p>Checked, not a {@link DomainException} subclass: {@code KernelErrorCodesTest}'s catalog of
 * eight codes is closed over {@link Money} and {@link Percentage}'s own business rules ("these
 * eight codes are definitive"), and an authentication-tag mismatch during column decryption is not
 * one of those two domains' business conditions.
 *
 * <p>The message never repeats the key, IV, additional authenticated data or plaintext (CLAUDE.md,
 * regla 11): none of the three is available to this exception in the first place, which is exactly
 * the point of an authenticated cipher failing closed.
 */
public final class AeadIntegrityException extends Exception {

    AeadIntegrityException(Throwable cause) {
        super("AES-GCM authentication failed: the tag does not match the ciphertext and the "
                + "additional authenticated data presented", cause);
    }
}
