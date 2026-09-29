package com.confia.identity.infrastructure;

import com.confia.identity.application.RecoveryCodeHasher;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.StoredRecoveryCodeHash;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * Hashes and verifies MFA recovery codes with Argon2id directly through Bouncy Castle, reusing the
 * exact profile, pepper and PHC codec {@link BouncyCastleArgon2PasswordHasher} already uses for
 * passwords (column-encryption-and-mfa-totp design.md, decision 5 / proposal.md D5: "el perfil
 * Argon2id, el códec del formato PHC y el hasher de bajo nivel — misma pimienta, mismo formato
 * {@code $argon2id$}") — behind {@link RecoveryCodeHasher}, its own port, never {@code
 * PasswordHasher} reopened.
 *
 * <p>No associated data, matching {@link BouncyCastleArgon2PasswordHasher}'s own reasoning: this
 * module has no per-hash context to bind, so every call passes an empty array.
 *
 * <p><b>No decoy hash.</b> Unlike password verification, a recovery code is checked only after the
 * caller already presented a valid password and, ordinarily, an already-known account
 * ({@code SecondFactorRequired}) — there is no "does this account exist" oracle to protect against
 * here, the same reasoning column-encryption-and-mfa-totp design.md, §4.3 already applies to why
 * this flow needs no {@code LoginInstitutionProvider} guard either. Nothing in specs/identity/spec.md
 * asks for one.
 */
public final class BouncyCastleRecoveryCodeHasher implements RecoveryCodeHasher {

    private static final byte[] NO_ASSOCIATED_DATA = new byte[0];

    private final Argon2Profile profile;
    private final Argon2Pepper pepper;
    private final SecureRandom secureRandom;
    private final Argon2RawHasher rawHasher;

    public BouncyCastleRecoveryCodeHasher(Argon2Profile profile, Argon2Pepper pepper) {
        this(profile, pepper, new SecureRandom());
    }

    BouncyCastleRecoveryCodeHasher(Argon2Profile profile, Argon2Pepper pepper,
            SecureRandom secureRandom) {
        this(profile, pepper, secureRandom, Argon2PhcCodec::rawHash);
    }

    /** The full constructor: {@code rawHasher} is the same testing seam
     * {@link BouncyCastleArgon2PasswordHasher} already establishes. */
    BouncyCastleRecoveryCodeHasher(Argon2Profile profile, Argon2Pepper pepper,
            SecureRandom secureRandom, Argon2RawHasher rawHasher) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.pepper = Objects.requireNonNull(pepper, "pepper");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.rawHasher = Objects.requireNonNull(rawHasher, "rawHasher");
    }

    /** Hashes {@code code} with a fresh random salt, using this instance's current profile. */
    @Override
    public StoredRecoveryCodeHash hash(PlainRecoveryCode code) {
        Objects.requireNonNull(code, "code");
        byte[] salt = new byte[profile.saltLength()];
        secureRandom.nextBytes(salt);
        byte[] tag = rawHasher.rawHash(codeBytes(code), salt, pepper.value(),
                NO_ASSOCIATED_DATA, profile.memoryCostKib(), profile.timeCost(),
                profile.parallelism(), profile.hashLength());
        return new StoredRecoveryCodeHash(Argon2PhcCodec.encode(profile, salt, tag));
    }

    /**
     * Verifies {@code code} against {@code storedHash}, using the parameters and salt embedded in
     * {@code storedHash} itself — never this instance's current profile, the same rule {@link
     * BouncyCastleArgon2PasswordHasher#matches} already follows for passwords.
     */
    @Override
    public boolean matches(PlainRecoveryCode code, StoredRecoveryCodeHash storedHash) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(storedHash, "storedHash");
        DecodedArgon2Hash decoded = Argon2PhcCodec.decode(storedHash.value());
        byte[] recomputed = rawHasher.rawHash(codeBytes(code), decoded.salt(),
                pepper.value(), NO_ASSOCIATED_DATA, decoded.memoryCostKib(), decoded.timeCost(),
                decoded.parallelism(), decoded.tag().length);
        return MessageDigest.isEqual(recomputed, decoded.tag());
    }

    private static byte[] codeBytes(PlainRecoveryCode code) {
        return code.value().getBytes(StandardCharsets.UTF_8);
    }
}
