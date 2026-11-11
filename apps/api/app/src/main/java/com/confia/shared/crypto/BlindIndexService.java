package com.confia.shared.crypto;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.Hmacs;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Blind indexes: a deterministic keyed digest of a sensitive value, stored beside its ciphertext so that
 * equality (and so uniqueness) can be checked in the database without decrypting anything. The digest is
 * HMAC-SHA-256 over the institution and the value, so the same value of two institutions gives two
 * different indexes and nothing about one institution can be matched against another.
 *
 * <p><b>The key is derived, not stored.</b> Each purpose (for example {@code students.national-id}) gets
 * its own key, {@code HMAC(masterKey, "confia/blind-index/v1/" + purpose)}, from the master key material
 * that already protects the data-encryption keys ({@link ColumnEncryptionMasterKey}). Compromising the
 * index key of one purpose reveals nothing about another, and there is no second key store. The
 * consequence to know: rotating the master key changes every index, so a rotation must recompute them
 * (the rotation procedure of the master key owns that step).
 *
 * <p>{@code toString()} is redacted and the derived keys are never exposed.
 */
public final class BlindIndexService {

    private static final String KEY_LABEL = "confia/blind-index/v1/";
    private static final Pattern PURPOSE = Pattern.compile("[a-z][a-z0-9.-]{0,63}");

    private final ColumnEncryptionMasterKey masterKey;

    public BlindIndexService(ColumnEncryptionMasterKey masterKey) {
        this.masterKey = Objects.requireNonNull(masterKey, "masterKey");
    }

    /**
     * The blind index of {@code value} for {@code purpose} in {@code institutionId}: 64 lowercase
     * hexadecimal characters.
     *
     * @param purpose a lowercase dotted name that identifies what is indexed, such as {@code
     *     students.national-id}
     * @param value the already normalised value; the caller owns the normalisation, because two
     *     spellings of the same value must give the same index
     */
    public String compute(String purpose, InstitutionId institutionId, String value) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(value, "value");
        if (!PURPOSE.matcher(purpose).matches()) {
            throw new IllegalArgumentException("a blind index purpose is a lowercase dotted name");
        }
        byte[] master = masterKey.value();
        byte[] key = Hmacs.sha256(master, (KEY_LABEL + purpose).getBytes(StandardCharsets.UTF_8));
        try {
            String message = institutionId.value() + ":" + value;
            return HexFormat.of().formatHex(Hmacs.sha256(key, message.getBytes(StandardCharsets.UTF_8)));
        } finally {
            Arrays.fill(master, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "BlindIndexService[REDACTED]";
    }
}
