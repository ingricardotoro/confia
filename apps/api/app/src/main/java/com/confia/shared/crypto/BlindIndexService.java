package com.confia.shared.crypto;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.Hmacs;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Blind indexes: a deterministic keyed digest of a sensitive value, stored beside its ciphertext so that
 * equality (and so uniqueness) can be checked in the database without decrypting anything. The digest is
 * HMAC-SHA-256 over the institution and the value, so the same value of two institutions gives two
 * different indexes and nothing about one institution can be matched against another.
 *
 * <p><b>The keys are dedicated and versioned</b> ({@link BlindIndexKeys}, docs/03-seguridad.md section
 * 7.3): they are not derived from the master key of the column encryption, so rotating one never touches
 * the other. Each purpose (for example {@code students.national-id}) gets its own key from the key of the
 * version, {@code HMAC(versionKey, "confia/blind-index/v1/" + purpose)}. The stored value is
 * {@code <version>:<64 lowercase hex>}, so an index says which key made it. A rotation adds the new
 * version, makes it current, recomputes the stored indexes in batches and then retires the old key;
 * until then {@link #candidates} lets a duplicate be found under every configured version.
 *
 * <p>{@code toString()} is redacted and the derived keys are never exposed.
 */
public final class BlindIndexService {

    private static final String KEY_LABEL = "confia/blind-index/v1/";
    private static final Pattern PURPOSE = Pattern.compile("[a-z][a-z0-9.-]{0,63}");

    private final BlindIndexKeys keys;

    public BlindIndexService(BlindIndexKeys keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    /** The version that makes new indexes. */
    public String currentVersion() {
        return keys.currentVersion();
    }

    /**
     * The blind index of {@code value} for {@code purpose} in {@code institutionId} under the current
     * version: {@code v1:} and 64 lowercase hexadecimal characters.
     *
     * @param purpose a lowercase dotted name that identifies what is indexed, such as {@code
     *     students.national-id}
     * @param value the already normalised value; the caller owns the normalisation, because two
     *     spellings of the same value must give the same index
     */
    public String compute(String purpose, InstitutionId institutionId, String value) {
        return computeUnder(keys.currentVersion(), purpose, institutionId, value);
    }

    /**
     * The indexes of the value under every configured version, the current one first: what a lookup of a
     * duplicate compares against, because a row may still carry the index of the previous version until
     * it is recomputed.
     */
    public List<String> candidates(String purpose, InstitutionId institutionId, String value) {
        List<String> all = new ArrayList<>();
        for (String version : keys.versions()) {
            all.add(computeUnder(version, purpose, institutionId, value));
        }
        return List.copyOf(all);
    }

    /** Whether {@code stored} was made under the current version. */
    public boolean isCurrent(String stored) {
        return stored != null && stored.startsWith(keys.currentVersion() + ":");
    }

    private String computeUnder(String version, String purpose, InstitutionId institutionId, String value) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(value, "value");
        if (!PURPOSE.matcher(purpose).matches()) {
            throw new IllegalArgumentException("a blind index purpose is a lowercase dotted name");
        }
        byte[] versionKey = keys.keyOf(version);
        byte[] key = Hmacs.sha256(versionKey, (KEY_LABEL + purpose).getBytes(StandardCharsets.UTF_8));
        try {
            String message = institutionId.value() + ":" + value;
            return version + ":" + HexFormat.of().formatHex(Hmacs.sha256(key, message.getBytes(StandardCharsets.UTF_8)));
        } finally {
            Arrays.fill(versionKey, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "BlindIndexService[REDACTED]";
    }
}
