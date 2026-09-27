package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StoredPasswordHash;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@link BouncyCastleArgon2PasswordHasher}: the round trip of hashing and verifying a real
 * password through Bouncy Castle's Argon2id, with the pepper applied as {@code secret} (design.md,
 * decision 6). The decoy hash this class also builds at construction time is task 2.3's own
 * addition and is tested there, not here.
 */
class BouncyCastleArgon2PasswordHasherTest {

    private static final Argon2Profile PROFILE = Argon2Profile.floor();
    private static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));

    private final BouncyCastleArgon2PasswordHasher hasher =
            new BouncyCastleArgon2PasswordHasher(PROFILE, PEPPER);

    @Test
    void producesAStoredHashInThePhcArgon2idFormat() {
        StoredPasswordHash hash = hasher.hash(PlainPassword.of("Segura#2026"));

        assertThat(hash.value()).startsWith("$argon2id$v=19$m=19456,t=3,p=1$");
    }

    @Test
    void matchesTheCorrectPasswordAgainstItsOwnHash() {
        PlainPassword password = PlainPassword.of("Segura#2026");
        StoredPasswordHash hash = hasher.hash(password);

        assertThat(hasher.matches(password, hash)).isTrue();
    }

    @Test
    void rejectsAnIncorrectPasswordAgainstTheStoredHash() {
        StoredPasswordHash hash = hasher.hash(PlainPassword.of("Segura#2026"));

        assertThat(hasher.matches(PlainPassword.of("otra-contraseña"), hash)).isFalse();
    }

    @Test
    void twoHashesOfTheSamePasswordUseDifferentRandomSaltsAndDiffer() {
        PlainPassword password = PlainPassword.of("Segura#2026");

        StoredPasswordHash first = hasher.hash(password);
        StoredPasswordHash second = hasher.hash(password);

        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(hasher.matches(password, first)).isTrue();
        assertThat(hasher.matches(password, second)).isTrue();
    }

    @Test
    void verificationUsesTheParametersStoredInTheHashNotTheCurrentProfile() {
        PlainPassword password = PlainPassword.of("Segura#2026");
        StoredPasswordHash hash = hasher.hash(password);

        Argon2Profile differentProfile = new Argon2Profile(19_456, 4, 1, 32, 16);
        BouncyCastleArgon2PasswordHasher hasherWithDifferentProfile =
                new BouncyCastleArgon2PasswordHasher(differentProfile, PEPPER);

        assertThat(hasherWithDifferentProfile.matches(password, hash)).isTrue();
    }

    @Test
    void aDifferentPepperFailsToVerifyAHashProducedUnderAnotherPepper() {
        PlainPassword password = PlainPassword.of("Segura#2026");
        StoredPasswordHash hash = hasher.hash(password);

        Argon2Pepper otherPepper =
                Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(repeat((byte) 0x2a, 32)));
        BouncyCastleArgon2PasswordHasher hasherWithOtherPepper =
                new BouncyCastleArgon2PasswordHasher(PROFILE, otherPepper);

        assertThat(hasherWithOtherPepper.matches(password, hash)).isFalse();
    }

    private static byte[] repeat(byte b, int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, b);
        return bytes;
    }
}
