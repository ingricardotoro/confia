package com.confia.shared.security.csrf;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Properties of {@link CsrfTokens} (session-endpoints-cookie-and-csrf design.md, decision 3): an
 * issued token always verifies against its refresh token, and a token altered in any single
 * position, whatever the replacement character, never does. The keys are generated for the run.
 */
class CsrfTokensProperties {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private final CsrfTokens tokens = new CsrfTokens(CsrfKeys.of(CsrfKeyFixtures.newKey(), null));

    @Property(tries = 300)
    void everyIssuedTokenVerifiesAgainstItsRefreshToken(@ForAll("refreshTokens") String refresh) {
        assertThat(tokens.verify(tokens.issue(refresh), refresh)).isTrue();
    }

    @Property(tries = 1000)
    void aTokenAlteredInOnePositionIsRejected(@ForAll("refreshTokens") String refresh,
            @ForAll @IntRange(min = 0, max = 86) int position,
            @ForAll("replacements") char replacement) {
        String token = tokens.issue(refresh);
        if (position == 43 || token.charAt(position) == replacement) {
            return;
        }
        String altered = token.substring(0, position) + replacement + token.substring(position + 1);

        assertThat(tokens.verify(altered, refresh)).isFalse();
    }

    @Property(tries = 300)
    void aTokenIsNeverAcceptedForADifferentRefreshToken(@ForAll("refreshTokens") String refresh,
            @ForAll("refreshTokens") String other) {
        if (refresh.equals(other)) {
            return;
        }

        assertThat(tokens.verify(tokens.issue(refresh), other)).isFalse();
    }

    @Provide
    Arbitrary<String> refreshTokens() {
        return Arbitraries.strings().withChars(ALPHABET).ofLength(43);
    }

    @Provide
    Arbitrary<Character> replacements() {
        return Arbitraries.chars().all();
    }
}
