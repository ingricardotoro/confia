package com.confia.kernel;

import java.util.Base64;
import java.util.Objects;

/**
 * The five-part stored format of {@code docs/03-seguridad.md} §7.3: {@code
 * v1:<id_dek>:<iv_b64>:<ciphertext_b64>:<tag_b64>} (design.md, decision 5). Purely a codec — it
 * never knows what a data-encryption key is, only that {@code dekId} is an opaque identifier
 * string it must format and parse back unchanged.
 *
 * <p>A plain record, with no {@code toString()} override: unlike this module's secret-carrying
 * value objects, this one transports only already-encrypted bytes and a text identifier, neither
 * of which needs redaction (design.md, §5, "record puro, sin secreto que redactar").
 *
 * <p><b>Equality is the record's own generated one, which compares {@code byte[]} components by
 * reference, not by content</b> (the standard {@code Objects.equals}-on-array gotcha). No override
 * was written to change this: nothing in this module needs {@code equals()}/{@code hashCode()} on
 * this type at all, and {@link EncryptedColumnValueTest} asserts its round trip field by field
 * with array-content comparison instead.
 */
public record EncryptedColumnValue(String dekId, byte[] iv, byte[] ciphertext, byte[] tag) {

    private static final String PREFIX = "v1";
    private static final int PART_COUNT = 5;

    public EncryptedColumnValue {
        Objects.requireNonNull(dekId, "dekId");
        Objects.requireNonNull(iv, "iv");
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(tag, "tag");
    }

    /**
     * @throws IllegalArgumentException if {@code stored} does not match {@code
     *     v1:<id_dek>:<iv_b64>:<ciphertext_b64>:<tag_b64>}. The message never repeats {@code
     *     stored} itself (CLAUDE.md, regla 11): the caller already has it, and an encrypted
     *     column value is exactly the kind of input this rule exists to keep out of a log line.
     */
    public static EncryptedColumnValue parse(String stored) {
        Objects.requireNonNull(stored, "stored");
        String[] parts = stored.split(":", -1);
        if (parts.length != PART_COUNT || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException(
                    "an encrypted column value must match exactly "
                            + "v1:<id_dek>:<iv_b64>:<ciphertext_b64>:<tag_b64>");
        }
        Base64.Decoder decoder = Base64.getDecoder();
        return new EncryptedColumnValue(parts[1], decoder.decode(parts[2]), decoder.decode(parts[3]),
                decoder.decode(parts[4]));
    }

    public String format() {
        Base64.Encoder encoder = Base64.getEncoder();
        return PREFIX + ":" + dekId + ":" + encoder.encodeToString(iv) + ":"
                + encoder.encodeToString(ciphertext) + ":" + encoder.encodeToString(tag);
    }
}
