package com.confia.notifications.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The keyed hash of a recipient's address, hex-encoded to exactly 64 lowercase characters
 * (transactional-email-adapter design.md decision 8; scenarios NT13 and NT14). It is what the
 * delivery log and the log lines keep instead of the address, which is personal data (docs/08).
 *
 * <p>The domain holds the value type and the one normalization that precedes hashing: {@code
 * strip()} and lower-casing with {@link Locale#ROOT}. The HMAC-SHA-256 itself, with the module's own
 * key, is computed in {@code notifications.application} through {@code shared.security.Digests},
 * because signing and hashing utilities are confined to {@code identity} and {@code shared.security}
 * (BI11 to BI13) and a domain may not depend on {@code shared}.
 *
 * <p>The constructor mirrors the future {@code CHECK (recipient_hash ~ '^[0-9a-f]{64}$')} of the
 * delivery log, so a value computed wrong fails here and not at the database. Not a secret in
 * itself, so the generated {@code toString()} stays: it is exactly what a log line may carry.
 */
public record RecipientHash(String value) {

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    public RecipientHash {
        Objects.requireNonNull(value, "value");
        if (!HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a recipient hash must be exactly 64 lowercase hex characters");
        }
    }

    /** The form of an address that is hashed: surrounding blanks removed, lower case, root locale. */
    public static String normalize(String address) {
        Objects.requireNonNull(address, "address");
        return address.strip().toLowerCase(Locale.ROOT);
    }
}
