package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
}
