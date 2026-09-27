package com.confia.identity.infrastructure;

import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StoredPasswordHash;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * Hashes and verifies passwords with Argon2id directly through Bouncy Castle, with the pepper
 * applied as {@code secret} (design.md, decision 6). Matches the eventual shape of the
 * {@code PasswordHasher} port {@code identity.application} declares in PR C3a
 * ({@code hash(PlainPassword): StoredPasswordHash}, {@code matches(PlainPassword,
 * StoredPasswordHash): boolean}) without implementing it yet — that port does not exist until that
 * later cut (design.md §11, paso 15).
 *
 * <p>No associated data is used: RFC 9106 §5.3's vector exercises it because the RFC vector itself
 * carries one, but this module has no per-hash context to bind (design.md, decision 6 names no
 * such requirement), so every real call passes an empty array.
 */
public final class BouncyCastleArgon2PasswordHasher {

    private static final byte[] NO_ASSOCIATED_DATA = new byte[0];

    private final Argon2Profile profile;
    private final Argon2Pepper pepper;
    private final SecureRandom secureRandom;

    public BouncyCastleArgon2PasswordHasher(Argon2Profile profile, Argon2Pepper pepper) {
        this(profile, pepper, new SecureRandom());
    }

    BouncyCastleArgon2PasswordHasher(Argon2Profile profile, Argon2Pepper pepper,
            SecureRandom secureRandom) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.pepper = Objects.requireNonNull(pepper, "pepper");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /** Hashes {@code password} with a fresh random salt, using this instance's current profile. */
    public StoredPasswordHash hash(PlainPassword password) {
        byte[] salt = new byte[profile.saltLength()];
        secureRandom.nextBytes(salt);
        return hash(password, salt);
    }

    /**
     * Hashes {@code password} with an explicit {@code salt}, using this instance's current
     * profile. Package-visible: its one production consumer is task 2.3's decoy construction
     * (design.md, decision 7, "el señuelo se calcula al construir el verificador"), which needs a
     * constant salt rather than a fresh random one every time.
     */
    StoredPasswordHash hash(PlainPassword password, byte[] salt) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(salt, "salt");
        byte[] tag = Argon2PhcCodec.rawHash(passwordBytes(password), salt, pepper.value(),
                NO_ASSOCIATED_DATA, profile.memoryCostKib(), profile.timeCost(),
                profile.parallelism(), profile.hashLength());
        return new StoredPasswordHash(Argon2PhcCodec.encode(profile, salt, tag));
    }

    /**
     * Verifies {@code password} against {@code storedHash}, using the parameters and salt embedded
     * in {@code storedHash} itself — never this instance's current profile (design.md, decision 6:
     * "La verificación usa los parámetros de la cadena almacenada, no los vigentes").
     */
    public boolean matches(PlainPassword password, StoredPasswordHash storedHash) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(storedHash, "storedHash");
        DecodedArgon2Hash decoded = Argon2PhcCodec.decode(storedHash.value());
        byte[] recomputed = Argon2PhcCodec.rawHash(passwordBytes(password), decoded.salt(),
                pepper.value(), NO_ASSOCIATED_DATA, decoded.memoryCostKib(), decoded.timeCost(),
                decoded.parallelism(), decoded.tag().length);
        return MessageDigest.isEqual(recomputed, decoded.tag());
    }

    private static byte[] passwordBytes(PlainPassword password) {
        return password.value().getBytes(StandardCharsets.UTF_8);
    }
}
