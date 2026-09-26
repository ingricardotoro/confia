package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StoredPasswordHash;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The decoy hash {@link BouncyCastleArgon2PasswordHasher} builds at construction time (design.md,
 * decision 7): "el señuelo se calcula al construir el verificador, hasheando una etiqueta constante
 * con los parámetros vigentes y la pimienta vigente, con una sal constante." Proven two ways: a
 * counting test double shows the underlying Argon2id computation runs exactly once for the decoy,
 * at construction — never lazily, never again on later use — and two hashers built with different
 * profiles produce different decoy hashes, which is what "calculado, no literal" actually means:
 * a hardcoded {@code $argon2id$...} literal could never track a profile change.
 */
class BouncyCastleArgon2PasswordHasherDecoyTest {

    private static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void buildsTheDecoyHashExactlyOnceAtConstructionAndNeverAgainOnLaterUse() {
        AtomicInteger invocations = new AtomicInteger();
        Argon2RawHasher countingRawHasher = (passwordBytes, salt, secret, associatedData,
                memoryCostKib, timeCost, parallelism, hashLength) -> {
            invocations.incrementAndGet();
            return Argon2PhcCodec.rawHash(passwordBytes, salt, secret, associatedData,
                    memoryCostKib, timeCost, parallelism, hashLength);
        };

        BouncyCastleArgon2PasswordHasher hasher = new BouncyCastleArgon2PasswordHasher(
                Argon2Profile.floor(), PEPPER, new SecureRandom(), countingRawHasher);

        assertThat(invocations.get())
                .as("the decoy hash must be computed exactly once, at construction")
                .isEqualTo(1);

        hasher.hash(PlainPassword.of("Segura#2026"));

        assertThat(invocations.get())
                .as("hashing a real password is separate work; it must not recompute the decoy")
                .isEqualTo(2);

        StoredPasswordHash firstAccess = hasher.decoyHash();
        StoredPasswordHash secondAccess = hasher.decoyHash();

        assertThat(firstAccess).isSameAs(secondAccess);
        assertThat(invocations.get())
                .as("reading the already-built decoy hash again must not recompute it")
                .isEqualTo(2);
    }

    @Test
    void theDecoyHashIsComputedFromTheCurrentProfileNotAHardcodedLiteral() {
        Argon2Profile floorProfile = Argon2Profile.floor();
        Argon2Profile differentProfile = new Argon2Profile(19_456, 4, 1, 32, 16);

        BouncyCastleArgon2PasswordHasher withFloorProfile =
                new BouncyCastleArgon2PasswordHasher(floorProfile, PEPPER);
        BouncyCastleArgon2PasswordHasher withDifferentProfile =
                new BouncyCastleArgon2PasswordHasher(differentProfile, PEPPER);

        assertThat(withFloorProfile.decoyHash().value())
                .isNotEqualTo(withDifferentProfile.decoyHash().value());
    }

    @Test
    void theDecoySaltIsExactly16Bytes() {
        BouncyCastleArgon2PasswordHasher hasher =
                new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), PEPPER);

        DecodedArgon2Hash decoded = Argon2PhcCodec.decode(hasher.decoyHash().value());

        assertThat(decoded.salt()).hasSize(16);
    }
}
