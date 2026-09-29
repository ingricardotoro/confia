package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@link AesGcmCipher}, byte to byte, against a real {@code javax.crypto.Cipher} (no mock, no
 * fake): "AES/GCM/NoPadding" is part of the JDK itself, so this suite runs with no container and
 * no external dependency (design.md, decision 5).
 */
class AesGcmCipherTest {

    private final AesGcmCipher cipher = new AesGcmCipher();

    @Test
    void encryptsAndDecryptsBackToTheOriginalPlaintext() throws AeadIntegrityException {
        byte[] key = bytes(32, (byte) 0x01);
        byte[] iv = bytes(12, (byte) 0x02);
        byte[] aad = "identity_mfa_totp_credential|encrypted_secret|inst-1:account-1"
                .getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = "a twenty-byte-plus TOTP secret!!".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertextWithTag = cipher.encrypt(key, iv, aad, plaintext);
        byte[] roundTripped = cipher.decrypt(key, iv, aad, ciphertextWithTag);

        assertThat(roundTripped).isEqualTo(plaintext);
    }

    /**
     * Confirms in code exactly what sonda S1 already observed against a real {@code SunJCE}
     * provider (apply-progress.md, task 1.1): {@code doFinal} concatenates the 128-bit
     * authentication tag to the end of the ciphertext, so the difference between the encrypted
     * output and the plaintext is always exactly 16 bytes.
     */
    @Test
    void encryptOutputIsExactlyPlaintextLengthPlusTheSixteenByteTag() {
        byte[] key = bytes(32, (byte) 0x03);
        byte[] iv = bytes(12, (byte) 0x04);
        byte[] aad = "shared_data_encryption_key|wrapped_key|inst-1:dek-1"
                .getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = bytes(22, (byte) 0x05);

        byte[] ciphertextWithTag = cipher.encrypt(key, iv, aad, plaintext);

        assertThat(ciphertextWithTag).hasSize(plaintext.length + 16);
    }

    /**
     * The escenario publicado "Un valor cifrado con los datos autenticados de una fila falla al
     * descifrarse con los de otra fila" (specs/identity/spec.md): decrypting with a different AAD
     * must fail with exactly {@link AeadIntegrityException}, the type sonda S1 confirmed
     * ({@code javax.crypto.AEADBadTagException}), never a generic failure.
     */
    @Test
    void decryptingWithADifferentAdditionalAuthenticatedDataFailsAuthentication() {
        byte[] key = bytes(32, (byte) 0x06);
        byte[] iv = bytes(12, (byte) 0x07);
        byte[] plaintext = "some encrypted column value".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertextWithTag = cipher.encrypt(key, iv,
                "identity_mfa_totp_credential|encrypted_secret|inst-1:account-1"
                        .getBytes(StandardCharsets.UTF_8),
                plaintext);

        assertThatThrownBy(() -> cipher.decrypt(key, iv,
                "identity_mfa_totp_credential|encrypted_secret|inst-1:account-2"
                        .getBytes(StandardCharsets.UTF_8),
                ciphertextWithTag))
                .isInstanceOf(AeadIntegrityException.class);
    }

    @Test
    void aKeyThatIsNotExactlyThirtyTwoBytesIsRejected() {
        assertThatThrownBy(() -> cipher.encrypt(bytes(16, (byte) 0x08), bytes(12, (byte) 0x09),
                new byte[0], new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIvThatIsNotExactlyTwelveBytesIsRejected() {
        assertThatThrownBy(() -> cipher.encrypt(bytes(32, (byte) 0x0A), bytes(16, (byte) 0x0B),
                new byte[0], new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int length, byte fill) {
        byte[] value = new byte[length];
        Arrays.fill(value, fill);
        return value;
    }
}
