package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HmacsTest {

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void matchesTheVectorsOfRfc4231() {
        byte[] key1 = new byte[20];
        Arrays.fill(key1, (byte) 0x0b);
        byte[] longKey = new byte[131];
        Arrays.fill(longKey, (byte) 0xaa);

        assertThat(HexFormat.of().formatHex(Hmacs.sha256(key1, ascii("Hi There"))))
                .isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
        assertThat(HexFormat.of().formatHex(Hmacs.sha256(ascii("Jefe"), ascii("what do ya want for nothing?"))))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        assertThat(HexFormat.of().formatHex(Hmacs.sha256(longKey,
                ascii("Test Using Larger Than Block-Size Key - Hash Key First"))))
                .isEqualTo("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54");
    }

    @Test
    void isThirtyTwoBytesAndDependsOnKeyAndMessage() {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32];
        random.nextBytes(key);
        byte[] other = key.clone();
        other[0] ^= 1;

        assertThat(Hmacs.sha256(key, ascii("a"))).hasSize(32)
                .isEqualTo(Hmacs.sha256(key, ascii("a")))
                .isNotEqualTo(Hmacs.sha256(key, ascii("b")))
                .isNotEqualTo(Hmacs.sha256(other, ascii("a")));
    }

    @Test
    void refusesAnEmptyKeyAndNulls() {
        assertThatThrownBy(() -> Hmacs.sha256(new byte[0], ascii("a"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hmacs.sha256(null, ascii("a"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Hmacs.sha256(ascii("k"), null)).isInstanceOf(NullPointerException.class);
    }
}
