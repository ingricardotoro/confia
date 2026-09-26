package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * {@link Argon2PhcCodec}, and specifically sonda S7 (design.md §10, §11 paso 9): the RFC 9106 §5.3
 * Argon2id test vector, compared byte-for-byte against the tag {@link Argon2PhcCodec#rawHash}
 * produces, including the {@code secret} parameter. This is the only deterministic way to prove
 * that {@code Argon2Parameters.Builder#withSecret(byte[])} is actually applied and not silently
 * ignored (design.md, decision 6): a hash that "works" without ever honoring the pepper is
 * indistinguishable from a correct one except against a known vector.
 *
 * <p>The vector's inputs and expected tag below are transcribed from RFC 9106 §5.3 as this agent
 * recalls them from training data — this sandbox has no network access to fetch the RFC directly.
 * If Bouncy Castle's output does not match, that is reported rather than adjusted to whatever this
 * implementation happens to produce (the task's own instruction): a codec that is merely
 * self-consistent, but different from the published vector, is exactly the defect this test exists
 * to catch.
 */
class Argon2PhcCodecTest {

    /** RFC 9106 §5.3: password is 32 bytes of 0x01. */
    private static final byte[] PASSWORD = repeat((byte) 0x01, 32);

    /** RFC 9106 §5.3: salt is 16 bytes of 0x02. */
    private static final byte[] SALT = repeat((byte) 0x02, 16);

    /** RFC 9106 §5.3: secret (the pepper's analogue in the vector) is 8 bytes of 0x03. */
    private static final byte[] SECRET = repeat((byte) 0x03, 8);

    /** RFC 9106 §5.3: associated data is 12 bytes of 0x04. */
    private static final byte[] ASSOCIATED_DATA = repeat((byte) 0x04, 12);

    private static final int MEMORY_COST_KIB = 32;
    private static final int TIME_COST = 3;
    private static final int PARALLELISM = 4;
    private static final int TAG_LENGTH = 32;

    /** RFC 9106 §5.3's own expected Argon2id tag for the parameters above. */
    private static final String EXPECTED_TAG_HEX =
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659";

    @Test
    void reproducesTheRfc9106Argon2idTestVectorByteForByteIncludingTheSecret() {
        byte[] tag = Argon2PhcCodec.rawHash(PASSWORD, SALT, SECRET, ASSOCIATED_DATA,
                MEMORY_COST_KIB, TIME_COST, PARALLELISM, TAG_LENGTH);

        assertThat(HexFormat.of().formatHex(tag)).isEqualTo(EXPECTED_TAG_HEX);
    }

    @Test
    void omittingTheSecretProducesADifferentTagThanTheVectorExpects() {
        byte[] noSecretTag = Argon2PhcCodec.rawHash(PASSWORD, SALT, new byte[0], ASSOCIATED_DATA,
                MEMORY_COST_KIB, TIME_COST, PARALLELISM, TAG_LENGTH);

        assertThat(HexFormat.of().formatHex(noSecretTag)).isNotEqualTo(EXPECTED_TAG_HEX);
    }

    @Test
    void encodeThenDecodeRoundTripsTheProfileSaltAndTagExactly() {
        Argon2Profile profile = Argon2Profile.floor();
        byte[] salt = repeat((byte) 0x07, profile.saltLength());
        byte[] tag = repeat((byte) 0x09, profile.hashLength());

        String phc = Argon2PhcCodec.encode(profile, salt, tag);
        DecodedArgon2Hash decoded = Argon2PhcCodec.decode(phc);

        assertThat(phc).startsWith("$argon2id$v=19$m=19456,t=3,p=1$");
        assertThat(decoded.memoryCostKib()).isEqualTo(profile.memoryCostKib());
        assertThat(decoded.timeCost()).isEqualTo(profile.timeCost());
        assertThat(decoded.parallelism()).isEqualTo(profile.parallelism());
        assertThat(decoded.salt()).isEqualTo(salt);
        assertThat(decoded.tag()).isEqualTo(tag);
    }

    @Test
    void decodeRejectsAStringThatIsNotAWellFormedPhcHash() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Argon2PhcCodec.decode("not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The case the previous test could not reach. {@code "not-a-hash"} carries nothing secret, so a
     * message that echoed its input passed that assertion while still leaking a real hash in
     * production. This input is shaped like a stored hash and carries recognizable salt and tag
     * segments, so the assertion fails if any of them reaches the message.
     */
    @Test
    void decodeNeverPutsTheHashItRejectedIntoTheExceptionMessage() {
        String recognizableSalt = "c2FsdHNhbHRzYWx0c2FsdA";
        String recognizableTag = "dGFndGFndGFndGFndGFndGFndGFndGFndGFn";
        String malformedButHashShaped =
                "$argon2id$v=19$m=19456,t=3$" + recognizableSalt + "$" + recognizableTag;

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> Argon2PhcCodec.decode(malformedButHashShaped))
                .isInstanceOf(IllegalArgumentException.class)
                .extracting(Throwable::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as("the delta requires that no exception message expose the resulting Argon2id "
                        + "hash, and this boundary is where a stored hash exists as a bare String, "
                        + "outside StoredPasswordHash and its redacted toString()")
                .doesNotContain(recognizableSalt)
                .doesNotContain(recognizableTag)
                .doesNotContain(malformedButHashShaped);
    }

    private static byte[] repeat(byte b, int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, b);
        return bytes;
    }
}
