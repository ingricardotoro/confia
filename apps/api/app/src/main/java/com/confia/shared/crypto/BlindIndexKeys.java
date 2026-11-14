package com.confia.shared.crypto;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The dedicated, versioned keys of the blind indexes (docs/03-seguridad.md, section 7.3). They are not
 * derived from the column-encryption master key: each version has key material of its own, with its own
 * rotation. Several versions are held at once during a rotation: new indexes are made with the current
 * one, and the lookups of duplicates also try the others until the stored indexes are recomputed.
 *
 * <p>A version is named {@code v} and one to three digits, without a leading zero ({@code v1}, {@code v12}).
 * Each key is the Base64 of exactly {@value #LENGTH_BYTES} bytes. Every message states what is wrong and
 * never the value, and no cause is chained, so no decoder detail can carry a fragment of the key.
 * {@code toString()} is redacted and the material is only handed to this package.
 */
public final class BlindIndexKeys {

    public static final int LENGTH_BYTES = 32;

    private static final Pattern VERSION = Pattern.compile("v[1-9][0-9]{0,2}");

    private final String currentVersion;
    private final Map<String, byte[]> keys;
    private final List<String> versions;

    private BlindIndexKeys(String currentVersion, Map<String, byte[]> keys) {
        this.currentVersion = currentVersion;
        this.keys = keys;
        List<String> others = new ArrayList<>(keys.keySet());
        others.remove(currentVersion);
        others.sort(Comparator.comparingInt(BlindIndexKeys::numberOf).reversed());
        List<String> ordered = new ArrayList<>();
        ordered.add(currentVersion);
        ordered.addAll(others);
        this.versions = List.copyOf(ordered);
    }

    /**
     * @param currentVersion the version that makes new indexes; it must be one of the configured ones
     * @param base64ByVersion the Base64 key of each version
     * @throws IllegalArgumentException if nothing is configured, a version or a key is malformed, or the
     *     current version has no key
     */
    public static BlindIndexKeys of(String currentVersion, Map<String, String> base64ByVersion) {
        Objects.requireNonNull(currentVersion, "currentVersion");
        Objects.requireNonNull(base64ByVersion, "base64ByVersion");
        if (base64ByVersion.isEmpty()) {
            throw new IllegalArgumentException("at least one blind-index key is required");
        }
        requireVersion(currentVersion);
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        base64ByVersion.forEach((version, base64) -> {
            requireVersion(version);
            decoded.put(version, decode(version, base64));
        });
        if (!decoded.containsKey(currentVersion)) {
            throw new IllegalArgumentException(
                    "the current blind-index version " + currentVersion + " has no key configured");
        }
        return new BlindIndexKeys(currentVersion, Map.copyOf(decoded));
    }

    /** Whether {@code version} has the shape of a version name. */
    public static boolean isVersionName(String version) {
        return version != null && VERSION.matcher(version).matches();
    }

    public String currentVersion() {
        return currentVersion;
    }

    /** The configured versions: the current one first, then the others, newest first. */
    public List<String> versions() {
        return versions;
    }

    public boolean has(String version) {
        return keys.containsKey(version);
    }

    /** A defensive copy of the key of {@code version}; for {@link BlindIndexService} only. */
    byte[] keyOf(String version) {
        byte[] key = keys.get(version);
        if (key == null) {
            throw new IllegalArgumentException("no blind-index key for version " + version);
        }
        return key.clone();
    }

    private static void requireVersion(String version) {
        if (!isVersionName(version)) {
            throw new IllegalArgumentException(
                    "a blind-index version is named v and one to three digits, without a leading zero");
        }
    }

    private static byte[] decode(String version, String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("the blind-index key of " + version + " must not be blank");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the blind-index key of " + version + " is not valid Base64");
        }
        if (decoded.length != LENGTH_BYTES) {
            throw new IllegalArgumentException("the blind-index key of " + version
                    + " must decode to exactly " + LENGTH_BYTES + " bytes, was " + decoded.length + " bytes");
        }
        return decoded;
    }

    private static int numberOf(String version) {
        return Integer.parseInt(version.substring(1));
    }

    /** Redacted deliberately: this holds the keys of the blind indexes. */
    @Override
    public String toString() {
        return "BlindIndexKeys[REDACTED]";
    }
}
