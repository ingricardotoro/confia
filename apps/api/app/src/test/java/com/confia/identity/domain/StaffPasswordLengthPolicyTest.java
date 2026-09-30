package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.StaffPasswordLengthPolicy.Accepted;
import com.confia.identity.domain.StaffPasswordLengthPolicy.TooLong;
import com.confia.identity.domain.StaffPasswordLengthPolicy.TooShort;
import java.text.Normalizer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * {@link StaffPasswordLengthPolicy} (password-recovery-token design.md decision 8;
 * specs/identity/spec.md, "Los bordes de 12 y 128 caracteres" and "La longitud se cuenta en
 * caracteres, no en unidades de codificación"). Lengths are code points after NFKC, never UTF-16
 * units, and there is no composition rule: lowercase letters alone are enough.
 */
class StaffPasswordLengthPolicyTest {

    private final StaffPasswordLengthPolicy policy = new StaffPasswordLengthPolicy();

    @Test
    void elevenCodePointsIsTooShortAndTwelveIsAccepted() {
        assertThat(policy.check("a".repeat(11))).isInstanceOf(TooShort.class);
        assertThat(policy.check("a".repeat(12))).isInstanceOf(Accepted.class);
    }

    @Test
    void oneHundredTwentyEightCodePointsIsAcceptedAndOneHundredTwentyNineIsTooLong() {
        assertThat(policy.check("a".repeat(128))).isInstanceOf(Accepted.class);
        assertThat(policy.check("a".repeat(129))).isInstanceOf(TooLong.class);
    }

    @Test
    void theBordersAreTheDomainConstants() {
        assertThat(StaffPasswordLengthPolicy.MIN_CODE_POINTS).isEqualTo(12);
        assertThat(StaffPasswordLengthPolicy.MAX_CODE_POINTS).isEqualTo(128);
    }

    /**
     * The two literals of the approved scenario, confirmed by sonda S4: NFKC leaves both unchanged,
     * at 11 code points in 13 UTF-16 units and 12 in 14. Counting UTF-16 units would accept the
     * first one.
     */
    @Test
    void theScenarioLiteralsAreCountedInCodePointsNotUtf16Units() {
        String elevenInThirteenUnits = "casa azul🌋🌊";
        String twelveInFourteenUnits = "casa azul 🌋🌊";
        assertThat(elevenInThirteenUnits).hasSize(13);
        assertThat(twelveInFourteenUnits).hasSize(14);

        assertThat(policy.check(elevenInThirteenUnits)).isInstanceOf(TooShort.class);
        assertThat(policy.check(twelveInFourteenUnits)).isInstanceOf(Accepted.class);
    }

    /** Fullwidth letters fold to ASCII under NFKC, so the count is taken after normalization. */
    @Test
    void theCountIsTakenAfterNfkc() {
        String twelveFullwidth = "ａ".repeat(12);

        Accepted accepted = (Accepted) policy.check(twelveFullwidth);

        assertThat(accepted.password()).isEqualTo(PlainPassword.of("a".repeat(12)));
    }

    @Test
    void anAcceptedValueCarriesThePlainPasswordWithoutThrowing() {
        String value = "correcto caballo bateria";

        Accepted accepted = (Accepted) policy.check(value);

        assertThat(accepted.password()).isEqualTo(PlainPassword.of(value));
        assertThat(accepted.toString()).doesNotContain(value);
    }

    @Test
    void anEmptyValueIsTooShortRatherThanAnException() {
        assertThat(policy.check("")).isInstanceOf(TooShort.class);
    }

    @Test
    void nullIsAProgrammingError() {
        assertThatThrownBy(() -> policy.check(null)).isInstanceOf(NullPointerException.class);
    }

    @Property
    void everyValueOfTwelveToOneHundredTwentyEightCodePointsAfterNfkcIsAccepted(
            @ForAll("inRangePasswords") String raw) {
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        int codePoints = normalized.codePointCount(0, normalized.length());
        Assume.that(codePoints >= 12 && codePoints <= 128);

        StaffPasswordLengthPolicy.Result result = policy.check(raw);

        assertThat(result).isInstanceOf(Accepted.class);
        assertThat(((Accepted) result).password().value()).isEqualTo(normalized);
    }

    @Property
    void everyValueOutsideTheRangeAfterNfkcIsRejected(@ForAll("candidatePasswords") String raw) {
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        int codePoints = normalized.codePointCount(0, normalized.length());
        Assume.that(codePoints < 12 || codePoints > 128);

        assertThat(policy.check(raw)).isInstanceOf(codePoints < 12 ? TooShort.class : TooLong.class);
    }

    /** Any Unicode, emoji outside the basic plane included, of 12 to 128 code points before NFKC. */
    @Provide
    Arbitrary<String> inRangePasswords() {
        return strings(MIN_GENERATED_IN_RANGE, MAX_GENERATED_IN_RANGE);
    }

    /** The same alphabet, of 0 to 140 code points, so both borders are crossed. */
    @Provide
    Arbitrary<String> candidatePasswords() {
        return strings(0, 140);
    }

    private static final int MIN_GENERATED_IN_RANGE = 12;
    private static final int MAX_GENERATED_IN_RANGE = 128;

    private static Arbitrary<String> strings(int minCodePoints, int maxCodePoints) {
        return Arbitraries.integers().between(0x20, 0x1FAFF)
                .filter(cp -> cp < Character.MIN_SURROGATE || cp > Character.MAX_SURROGATE)
                .map(Character::toString)
                .list().ofMinSize(minCodePoints).ofMaxSize(maxCodePoints)
                .map(parts -> String.join("", parts));
    }
}
