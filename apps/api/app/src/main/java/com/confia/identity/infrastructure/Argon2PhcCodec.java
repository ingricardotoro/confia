package com.confia.identity.infrastructure;

import java.util.Base64;
import java.util.Objects;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Runs Argon2id directly through Bouncy Castle, and encodes/decodes the stored PHC
 * {@code $argon2id$v=19$m=...,t=...,p=...$<salt>$<tag>} format (design.md, decision 6). The only
 * class in this module that touches {@code org.bouncycastle.crypto..} directly, so every caller —
 * {@link BouncyCastleArgon2PasswordHasher} and this class's own RFC 9106 §5.3 vector test — shares
 * the one call site that proves the pepper is applied as Argon2id's {@code secret} parameter and
 * never silently ignored (sonda S7).
 */
public final class Argon2PhcCodec {

    private static final String ARGON2ID_SEGMENT = "argon2id";
    private static final String PREFIX = "$" + ARGON2ID_SEGMENT + "$v=";

    private Argon2PhcCodec() {
    }

    /**
     * Runs Argon2id with every parameter explicit, including {@code secret} — never omitted,
     * because a call that silently ignores {@code secret} produces a tag indistinguishable from a
     * correct one except against a known vector (design.md, decision 6). This is the exact call
     * the RFC 9106 §5.3 test vector exercises, byte for byte.
     */
    public static byte[] rawHash(byte[] passwordBytes, byte[] salt, byte[] secret,
            byte[] associatedData, int memoryCostKib, int timeCost, int parallelism,
            int hashLength) {
        Objects.requireNonNull(passwordBytes, "passwordBytes");
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(associatedData, "associatedData");
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryCostKib)
                .withIterations(timeCost)
                .withParallelism(parallelism)
                .withSalt(salt)
                .withSecret(secret)
                .withAdditional(associatedData)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] tag = new byte[hashLength];
        generator.generateBytes(passwordBytes, tag);
        return tag;
    }

    /** Builds the stored {@code $argon2id$v=19$m=...,t=...,p=...$<salt>$<tag>} string. */
    public static String encode(Argon2Profile profile, byte[] salt, byte[] tag) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(tag, "tag");
        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        return PREFIX + Argon2Parameters.ARGON2_VERSION_13
                + "$m=" + profile.memoryCostKib() + ",t=" + profile.timeCost()
                + ",p=" + profile.parallelism()
                + "$" + encoder.encodeToString(salt)
                + "$" + encoder.encodeToString(tag);
    }

    /**
     * Parses a stored PHC string back into its parameters, salt and tag, verbatim — never against
     * the currently-vigent profile (design.md, decision 6: "La verificación usa los parámetros de
     * la cadena almacenada, no los vigentes").
     */
    static DecodedArgon2Hash decode(String phc) {
        Objects.requireNonNull(phc, "phc");
        String[] parts = phc.split("\\$", -1);
        if (parts.length != 6 || !parts[0].isEmpty() || !ARGON2ID_SEGMENT.equals(parts[1])
                || !parts[2].startsWith("v=")) {
            throw new IllegalArgumentException("not a well-formed $argon2id$ hash: " + phc);
        }
        int version;
        int memoryCostKib;
        int timeCost;
        int parallelism;
        byte[] salt;
        byte[] tag;
        try {
            version = Integer.parseInt(parts[2].substring("v=".length()));
            String[] paramParts = parts[3].split(",", -1);
            if (paramParts.length != 3) {
                throw new IllegalArgumentException("not a well-formed $argon2id$ hash: " + phc);
            }
            memoryCostKib = Integer.parseInt(paramParts[0].substring("m=".length()));
            timeCost = Integer.parseInt(paramParts[1].substring("t=".length()));
            parallelism = Integer.parseInt(paramParts[2].substring("p=".length()));
            salt = Base64.getDecoder().decode(parts[4]);
            tag = Base64.getDecoder().decode(parts[5]);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("not a well-formed $argon2id$ hash: " + phc, e);
        }
        return new DecodedArgon2Hash(version, memoryCostKib, timeCost, parallelism, salt, tag);
    }
}
