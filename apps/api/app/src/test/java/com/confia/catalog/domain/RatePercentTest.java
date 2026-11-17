package com.confia.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The percentages in these tests are inputs of the cases, never values the system knows. */
class RatePercentTest {

    @ParameterizedTest
    @ValueSource(strings = {"0.0001", "1", "7.5", "12.3456", "99.9999", "100", "100.0", "100.0000", "007", "0.5"})
    void aPositivePercentUpToOneHundredWithAtMostFourDecimalsIsAccepted(String text) {
        RatePercent percent = RatePercent.of(text);

        assertThat(percent.value().scale()).isEqualTo(4);
        assertThat(percent.value()).isEqualByComparingTo(new BigDecimal(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.0000", "-1", "-0.0001", "+5", "100.0001", "101", "1000", "5.00001",
            "12.34567", "1e1", "1E+1", "NaN", "Infinity", "", " ", " 5", "5 ", "5,5", ".5", "5.", "0x10", "٥"})
    void zeroNegativeAboveOneHundredMoreThanFourDecimalsAndAnythingThatIsNotPlainDigitsIsRefused(String text) {
        assertThatThrownBy(() -> RatePercent.of(text)).isInstanceOf(InvalidCatalogValueException.class);
    }

    @ParameterizedTest
    @NullSource
    void aMissingTextIsRefused(String text) {
        assertThatThrownBy(() -> RatePercent.of(text)).isInstanceOf(InvalidCatalogValueException.class);
    }

    @Test
    void thePercentageIsHeldAtScaleFourSoEqualPercentagesAreEqualValues() {
        assertThat(RatePercent.of("5")).isEqualTo(RatePercent.of("5.0000")).isEqualTo(RatePercent.of("5.00"));
        assertThat(RatePercent.of("5")).isNotEqualTo(RatePercent.of("5.0001"));
        assertThat(RatePercent.of("5").toPlainString()).isEqualTo("5.0000");
        assertThat(RatePercent.of("100").toPlainString()).isEqualTo("100.0000");
    }

    @Test
    void theConstructorAppliesTheSameRulesToAValueThatDoesNotComeFromText() {
        assertThatThrownBy(() -> new RatePercent(null)).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> new RatePercent(BigDecimal.ZERO)).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> new RatePercent(new BigDecimal("100.00001")))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThat(new RatePercent(new BigDecimal("15.00000")).value().scale()).isEqualTo(4);
    }

    @Property
    void anyPercentageWithUpToFourDecimalsBetweenTheStepAndOneHundredRoundTripsExactly(
            @ForAll @IntRange(min = 1, max = 1_000_000) int tenThousandths) {
        BigDecimal exact = BigDecimal.valueOf(tenThousandths, 4);

        RatePercent percent = RatePercent.of(exact.toPlainString());

        assertThat(percent.value()).isEqualByComparingTo(exact);
        assertThat(RatePercent.of(percent.toPlainString())).isEqualTo(percent);
    }
}
