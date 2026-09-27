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
 *
 * <p><b>The decoy hash (design.md, decision 7).</b> Built once, at construction, by hashing a
 * constant label with a constant salt — under this instance's current profile and pepper, never a
 * hardcoded {@code $argon2id$...} literal, so a change to {@code memoryCost} can never leave the
 * decoy cheaper than a real verification and quietly emptying out the timing control it exists to
 * provide. "Constant" means constant per process: the decoy is never persisted, never compared
 * across instances, and never exposed outside this package (ADR-0018's own reasoning for why a
 * uniform-cost check on a nonexistent account belongs here, next to the real check, and not in a
 * later cut that could forget to keep the two in step).
 */
public final class BouncyCastleArgon2PasswordHasher {

    /** Public only for this class's own Javadoc precision; never persisted, never compared. */
    static final String DECOY_LABEL = "confia.identity.decoy.v1";

    /**
     * A constant, non-secret 16-byte salt (design.md, decision 7: "sal constante de 16 bytes,
     * literal en el código: una sal no es un secreto"). Its content carries no meaning beyond being
     * fixed and exactly {@link Argon2Profile#FLOOR_SALT_LENGTH_BYTES} long.
     */
    private static final byte[] DECOY_SALT = {
            0x63, 0x6f, 0x6e, 0x66, 0x69, 0x61, 0x2d, 0x64, 0x65, 0x63, 0x6f, 0x79, 0x2d, 0x76,
            0x31, 0x00
    };

    private static final byte[] NO_ASSOCIATED_DATA = new byte[0];

    private final Argon2Profile profile;
    private final Argon2Pepper pepper;
    private final SecureRandom secureRandom;
    private final Argon2RawHasher rawHasher;
    private final StoredPasswordHash decoyHash;

    public BouncyCastleArgon2PasswordHasher(Argon2Profile profile, Argon2Pepper pepper) {
        this(profile, pepper, new SecureRandom());
    }

    BouncyCastleArgon2PasswordHasher(Argon2Profile profile, Argon2Pepper pepper,
            SecureRandom secureRandom) {
        this(profile, pepper, secureRandom, Argon2PhcCodec::rawHash);
    }

    /**
     * The full constructor: {@code rawHasher} is a testing seam
     * (design.md, decision 7, "Cómo se demuestra que el señuelo se ejecuta") that lets a test count
     * how many times the underlying Argon2id computation runs, to prove the decoy below is built
     * exactly once, at construction.
     */
    BouncyCastleArgon2PasswordHasher(Argon2Profile profile, Argon2Pepper pepper,
            SecureRandom secureRandom, Argon2RawHasher rawHasher) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.pepper = Objects.requireNonNull(pepper, "pepper");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.rawHasher = Objects.requireNonNull(rawHasher, "rawHasher");
        this.decoyHash = hash(PlainPassword.of(DECOY_LABEL), DECOY_SALT);
    }

    /** Hashes {@code password} with a fresh random salt, using this instance's current profile. */
    public StoredPasswordHash hash(PlainPassword password) {
        byte[] salt = new byte[profile.saltLength()];
        secureRandom.nextBytes(salt);
        return hash(password, salt);
    }

    /**
     * Hashes {@code password} with an explicit {@code salt}, using this instance's current
     * profile. Package-visible: its one production consumer is the decoy this constructor builds,
     * which needs a constant salt rather than a fresh random one every time.
     */
    StoredPasswordHash hash(PlainPassword password, byte[] salt) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(salt, "salt");
        byte[] tag = rawHasher.rawHash(passwordBytes(password), salt, pepper.value(),
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
        byte[] recomputed = rawHasher.rawHash(passwordBytes(password), decoded.salt(),
                pepper.value(), NO_ASSOCIATED_DATA, decoded.memoryCostKib(), decoded.timeCost(),
                decoded.parallelism(), decoded.tag().length);
        return MessageDigest.isEqual(recomputed, decoded.tag());
    }

    /**
     * The decoy hash built once at this instance's construction. Package-visible: its production
     * use (verifying against it when an account does not exist) is application-layer wiring that
     * arrives in PR C3a; this package only guarantees the decoy exists and was computed once.
     */
    StoredPasswordHash decoyHash() {
        return decoyHash;
    }

    private static byte[] passwordBytes(PlainPassword password) {
        return password.value().getBytes(StandardCharsets.UTF_8);
    }
}
