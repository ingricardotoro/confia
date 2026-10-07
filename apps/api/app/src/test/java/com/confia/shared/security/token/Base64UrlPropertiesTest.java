package com.confia.shared.security.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.statistics.Statistics;

/**
 * {@link Base64Url} against the JDK's own encoder and decoder (session-tokens-and-web-layer
 * design.md, decision 2). The JDK encodes the canonical way, so the round trip must be exact; the JDK
 * decodes leniently, so the strict decoder must accept exactly the texts the JDK's encoder could have
 * produced and refuse the rest, which is the malleability of a token with two texts.
 */
class Base64UrlPropertiesTest {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    @Property(tries = 500)
    void encodesLikeTheJdkAndDecodesBackToTheSameBytes(@ForAll byte[] bytes) {
        String encoded = Base64Url.encode(bytes);

        assertThat(encoded).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        assertThat(Base64Url.decode(encoded))
                .hasValueSatisfying(decoded -> assertThat(decoded).isEqualTo(bytes));
    }

    @Property(tries = 1000)
    void acceptsExactlyTheTextsTheJdkEncoderCouldHaveProduced(@ForAll("alphabetTexts") String text) {
        boolean canonical = isCanonicalAccordingToTheJdk(text);

        Optional<byte[]> decoded = Base64Url.decode(text);

        Statistics.collect(canonical ? "canonical" : "non-canonical");
        assertThat(decoded.isPresent()).isEqualTo(canonical);
        decoded.ifPresent(bytes -> assertThat(bytes).isEqualTo(Base64.getUrlDecoder().decode(text)));
    }

    @Property(tries = 500)
    void anyCharacterOutsideTheAlphabetMakesTheTextInvalid(@ForAll byte[] bytes,
            @ForAll @IntRange(min = 0, max = 4095) int position,
            @ForAll("foreignCharacters") char foreign) {
        String encoded = Base64Url.encode(bytes);
        int at = position % (encoded.length() + 1);
        String spoiled = encoded.substring(0, at) + foreign + encoded.substring(at);

        assertThat(Base64Url.decode(spoiled)).isEmpty();
    }

    @Provide
    Arbitrary<String> alphabetTexts() {
        return Arbitraries.strings().withChars(ALPHABET).ofMinLength(0).ofMaxLength(48);
    }

    @Provide
    Arbitrary<Character> foreignCharacters() {
        return Arbitraries.of('=', '+', '/', ' ', '\n', '\r', '\t', '.', '!', (char) 0, (char) 0xe9,
                (char) 0x2028);
    }

    private static boolean isCanonicalAccordingToTheJdk(String text) {
        if (text.length() % 4 == 1) {
            return false;
        }
        byte[] lenient = Base64.getUrlDecoder().decode(text);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(lenient).equals(text);
    }
}
