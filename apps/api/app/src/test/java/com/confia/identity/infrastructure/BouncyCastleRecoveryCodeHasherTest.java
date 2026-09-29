package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.StoredRecoveryCodeHash;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@link BouncyCastleRecoveryCodeHasher}: the round trip of hashing and verifying a real recovery
 * code through Bouncy Castle's Argon2id, with the same profile, pepper and PHC codec {@link
 * BouncyCastleArgon2PasswordHasherTest} already proves for passwords (column-encryption-and-mfa-totp
 * design.md, decision 5 / proposal.md D5: reused, never reopened).
 */
class BouncyCastleRecoveryCodeHasherTest {

    private static final Argon2Profile PROFILE = Argon2Profile.floor();
    private static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));

    private final BouncyCastleRecoveryCodeHasher hasher =
            new BouncyCastleRecoveryCodeHasher(PROFILE, PEPPER);

    @Test
    void producesAStoredHashInThePhcArgon2idFormat() {
        StoredRecoveryCodeHash hash = hasher.hash(PlainRecoveryCode.generate(new SecureRandom()));

        assertThat(hash.value()).startsWith("$argon2id$v=19$m=19456,t=3,p=1$");
    }

    @Test
    void matchesTheCorrectCodeAgainstItsOwnHash() {
        PlainRecoveryCode code = PlainRecoveryCode.generate(new SecureRandom());
        StoredRecoveryCodeHash hash = hasher.hash(code);

        assertThat(hasher.matches(code, hash)).isTrue();
    }

    @Test
    void rejectsADifferentCodeAgainstTheStoredHash() {
        PlainRecoveryCode code = PlainRecoveryCode.generate(new SecureRandom());
        StoredRecoveryCodeHash hash = hasher.hash(code);

        PlainRecoveryCode otherCode = PlainRecoveryCode.generate(new SecureRandom());

        assertThat(hasher.matches(otherCode, hash)).isFalse();
    }

    @Test
    void twoHashesOfTheSameCodeUseDifferentRandomSaltsAndDiffer() {
        PlainRecoveryCode code = PlainRecoveryCode.generate(new SecureRandom());

        StoredRecoveryCodeHash first = hasher.hash(code);
        StoredRecoveryCodeHash second = hasher.hash(code);

        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(hasher.matches(code, first)).isTrue();
        assertThat(hasher.matches(code, second)).isTrue();
    }

    @Test
    void verificationUsesTheParametersStoredInTheHashNotTheCurrentProfile() {
        PlainRecoveryCode code = PlainRecoveryCode.generate(new SecureRandom());
        StoredRecoveryCodeHash hash = hasher.hash(code);

        Argon2Profile differentProfile = new Argon2Profile(19_456, 4, 1, 32, 16);
        BouncyCastleRecoveryCodeHasher hasherWithDifferentProfile =
                new BouncyCastleRecoveryCodeHasher(differentProfile, PEPPER);

        assertThat(hasherWithDifferentProfile.matches(code, hash)).isTrue();
    }
}
