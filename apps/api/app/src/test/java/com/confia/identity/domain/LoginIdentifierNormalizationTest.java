package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Sonda S8 (design.md §10, bloqueante de C1 y C3): confirms that no normalized form
 * {@link LoginIdentifier#of(String)} produces (trim, NFKC, then {@code toLowerCase(Locale.ROOT)})
 * would ever violate {@code identity_staff_account}'s {@code CHECK (email !~ '[A-Z]')}
 * (design.md, decision 4, point 1) — the whole reason that {@code CHECK} uses a plain ASCII
 * regular expression instead of the collation-dependent {@code lower()}.
 *
 * <p>Samples, exactly as {@code apply-progress.md} names them: the Turkish {@code İ} (U+0130,
 * design.md's own "classic counterexample" for collation mismatch), the German {@code ß}
 * (already lowercase, and never case-folded to {@code SS} by {@code toLowerCase}), a Greek capital
 * ({@code Σ}, whose lowercase differs by word-final position) and a CJK ideograph sequence, which
 * carries no case at all. Also covers the Kelvin sign (U+212A), the one common case where NFKC
 * itself decomposes into a plain ASCII uppercase letter ({@code K}) before {@code toLowerCase}
 * runs — precisely why normalization applies NFKC <b>before</b> lowering, never after.
 *
 * <p><b>Result: no sample produced an ASCII uppercase letter.</b> Recorded verbatim in
 * {@code apply-progress.md}, task 1.3, sonda S8. Consequence: {@code LoginIdentifier}'s accepted
 * character set needed no narrowing before writing {@code V5}, and the {@code CHECK} itself is
 * written unchanged, exactly as design.md decision 4 already specifies.
 */
class LoginIdentifierNormalizationTest {

    private static final Pattern ASCII_UPPERCASE = Pattern.compile("[A-Z]");

    @ParameterizedTest
    @ValueSource(strings = {
            // Turkish İ (U+0130), the exact counterexample design.md decision 4 names.
            "uİser@colegio.edu.hn",
            // German ß (U+00DF), already lowercase; toLowerCase must not touch it, and it must
            // never have been case-folded to "SS" either.
            "straße@colegio.edu.hn",
            // Greek capital sigma (U+03A3), word-medial and word-final position.
            "ΣΣΣ@colegio.edu.hn",
            // CJK ideographs (no case at all): 日本語 ("Japanese language").
            "日本語@colegio.edu.hn",
            // Kelvin sign (U+212A): NFKC decomposes it to ASCII 'K' BEFORE lowering runs.
            "Kvin@colegio.edu.hn",
            // All five combined in one identifier, to rule out an interaction between them.
            "uİser.straße.Σ.日本語.K@colegio.edu.hn"
    })
    void noNormalizedFormEverContainsAnAsciiUppercaseLetter(String raw) {
        LoginIdentifier normalized = LoginIdentifier.of(raw);

        assertThat(ASCII_UPPERCASE.matcher(normalized.value()).find())
                .as("normalized identifier '%s' (from raw '%s') must never contain an ASCII "
                        + "uppercase letter, or it would violate "
                        + "identity_staff_account_email_lower_chk", normalized.value(), raw)
                .isFalse();
    }

    @Test
    void normalizationIsIdempotent() {
        String raw = "  María.López@Colegio.EDU.HN  ";
        LoginIdentifier once = LoginIdentifier.of(raw);
        LoginIdentifier twice = LoginIdentifier.of(once.value());

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void trimsAndLowercasesAnOrdinaryAsciiEmail() {
        LoginIdentifier identifier = LoginIdentifier.of("  Maria.Lopez@Colegio.edu.hn  ");

        assertThat(identifier.value()).isEqualTo("maria.lopez@colegio.edu.hn");
    }

    @Test
    void rejectsANormalizedValueShorterThanThreeCharacters() {
        assertThatThrownBy(() -> new LoginIdentifier("ab"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANormalizedValueLongerThan320Characters() {
        String tooLong = "a".repeat(321);

        assertThatThrownBy(() -> new LoginIdentifier(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTheBoundaryLengthsOfThreeAndThreeHundredAndTwentyCharacters() {
        assertThat(new LoginIdentifier("abc").value()).isEqualTo("abc");
        assertThat(new LoginIdentifier("a".repeat(320)).value()).hasSize(320);
    }

    @Test
    void rejectsANullRawValue() {
        assertThatThrownBy(() -> LoginIdentifier.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullNormalizedValue() {
        assertThatThrownBy(() -> new LoginIdentifier(null))
                .isInstanceOf(NullPointerException.class);
    }
}
