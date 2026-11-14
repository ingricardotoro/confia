package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** SHA-256 known-answer vectors (FIPS 180-4 / NIST examples), so the utility's bytes are pinned. */
class DigestsTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void hashesTheEmptyInputToTheStandardVector() {
        assertThat(HEX.formatHex(Digests.sha256(new byte[0])))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void hashesAbcToTheStandardVector() {
        assertThat(HEX.formatHex(Digests.sha256("abc".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashesTheTwoBlockMessageToTheStandardVector() {
        byte[] input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(HEX.formatHex(Digests.sha256(input)))
                .isEqualTo("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    }

    @Test
    void returnsThirtyTwoBytesAndDoesNotRetainTheInput() {
        byte[] input = {1, 2, 3};

        byte[] first = Digests.sha256(input);
        input[0] = 9;
        byte[] second = Digests.sha256(new byte[] {1, 2, 3});

        assertThat(first).hasSize(32).isEqualTo(second);
    }

    private static byte[] key(int length, int fill) {
        byte[] key = new byte[length];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    @Test
    void hmacMatchesRfc4231TestCase6WithAKeyLargerThanTheBlockSize() {
        byte[] data = "Test Using Larger Than Block-Size Key - Hash Key First"
                .getBytes(StandardCharsets.US_ASCII);

        assertThat(HEX.formatHex(Digests.hmacSha256(key(131, 0xaa), data)))
                .isEqualTo("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54");
    }

    @Test
    void hmacMatchesRfc4231TestCase7WithALargerKeyAndLargerData() {
        byte[] data = ("This is a test using a larger than block-size key and a larger than "
                + "block-size data. The key needs to be hashed before being used by the HMAC "
                + "algorithm.").getBytes(StandardCharsets.US_ASCII);

        assertThat(HEX.formatHex(Digests.hmacSha256(key(131, 0xaa), data)))
                .isEqualTo("9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2");
    }

    @Test
    void hmacIsKeyedSoItDiffersFromThePlainDigestAndFromAnotherKey() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);

        byte[] keyed = Digests.hmacSha256(key(32, 1), data);

        assertThat(keyed).hasSize(32).isNotEqualTo(Digests.sha256(data))
                .isNotEqualTo(Digests.hmacSha256(key(32, 2), data))
                .isEqualTo(Digests.hmacSha256(key(32, 1), data));
    }

    @Test
    void hmacAcceptsA32ByteKeyAndRejectsA31ByteKeyWithoutRevealingIt() {
        byte[] data = {1};

        assertThat(Digests.hmacSha256(key(32, 0x11), data)).hasSize(32);
        assertThatThrownBy(() -> Digests.hmacSha256(key(31, 0x11), data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32")
                .hasMessageNotContaining("17");
    }

    @Test
    void hmacRejectsNullArguments() {
        assertThatThrownBy(() -> Digests.hmacSha256(null, new byte[0]))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Digests.hmacSha256(key(32, 1), null))
                .isInstanceOf(NullPointerException.class);
    }
}
