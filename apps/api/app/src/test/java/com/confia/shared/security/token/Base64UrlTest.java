package com.confia.shared.security.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Strict, canonical base64url (session-tokens-and-web-layer design.md, decision 2). The vectors are
 * those of RFC 4648 section 10, without padding; the rejections are the ones the JDK's lenient
 * decoder accepts in silence (I55).
 */
class Base64UrlTest {

    @ParameterizedTest
    @CsvSource(value = {"'',''", "f,Zg", "fo,Zm8", "foo,Zm9v", "foob,Zm9vYg", "fooba,Zm9vYmE",
            "foobar,Zm9vYmFy"})
    void encodesTheRfc4648VectorsWithoutPadding(String plain, String encoded) {
        assertThat(Base64Url.encode(plain.getBytes(StandardCharsets.US_ASCII))).isEqualTo(encoded);
        assertThat(Base64Url.decode(encoded)).hasValueSatisfying(
                bytes -> assertThat(new String(bytes, StandardCharsets.US_ASCII)).isEqualTo(plain));
    }

    @Test
    void usesTheUrlSafeAlphabet() {
        byte[] bytes = {(byte) 0xfb, (byte) 0xff, (byte) 0xbe};

        assertThat(Base64Url.encode(bytes)).isEqualTo("-_--");
        assertThat(Base64Url.decode("-_--"))
                .hasValueSatisfying(decoded -> assertThat(decoded).isEqualTo(bytes));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Zg==", "Zm8=", "Zm9v=", "+_--", "-/--", "Zm 9v", "Zm9v\n", "Zg ", " Zg",
            "Zm9v" + (char) 0xe9, "Zm9v" + (char) 0, "Z", "Zm9vY", "Zh", "Zm9", "Zm9vYh"})
    void rejectsPaddingTheStandardAlphabetWhitespaceBadLengthsAndTrailingBits(String text) {
        assertThat(Base64Url.decode(text)).isEqualTo(Optional.empty());
    }

    @Test
    void theJdkDecoderWouldHaveAcceptedTheNonCanonicalTexts() {
        // "Zh" and "Zm9vYh" carry non-zero trailing bits: the JDK decodes them to the same bytes as
        // "Zg" and "Zm9vYg", which is exactly the malleability the strict decoder removes.
        assertThat(java.util.Base64.getUrlDecoder().decode("Zh")).isEqualTo(new byte[] {'f'});
        assertThat(java.util.Base64.getUrlDecoder().decode("Zm9vYh"))
                .isEqualTo("foob".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base64Url.decode("Zg")).isPresent();
        assertThat(Base64Url.decode("Zm9vYg")).isPresent();
    }
}
