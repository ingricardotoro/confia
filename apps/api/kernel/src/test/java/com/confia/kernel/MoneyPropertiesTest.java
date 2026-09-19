package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * jqwik properties for {@code allocate} and for the coherence between {@link Money}'s exact
 * operations and an equivalent full-scale {@link BigDecimal} calculation (specs/money/spec.md,
 * requirements "Reparto proporcional sin pérdida de residuo (`allocate`)" and "Coherencia entre
 * operaciones intermedias y el cálculo a escala completa"; design.md, decision 7).
 *
 * <p>Generators are bounded well within {@code NUMERIC(14,4)}'s representable range so that a
 * legitimate range overflow never confounds a property about coherence, not about range checking
 * ({@code MoneyArithmeticTest} already owns overflow behavior). Tries are explicitly lowered from
 * the module default of 1000 ({@code junit-platform.properties}) to {@value #TRIES} per property:
 * PIT re-runs every property for every mutant, and five properties at the default would slow the
 * mutation run noticeably without adding meaningful extra coverage past a few hundred draws
 * (design.md, "Secuencia de implementación").
 */
class MoneyPropertiesTest {

    private static final int TRIES = 200;

    /** 12 nines: cents up to 9 999 999 999.99, well under NUMERIC(14,4)'s 9999999999.9999. */
    private static final long MAX_MINOR_UNITS = 999_999_999_999L;

    @Property(tries = TRIES)
    void allocateSumsExactlyToTheTotalAndNoPartIsNegativeForANonNegativeTotal(
            @ForAll("nonNegativeAmounts") Money total, @ForAll("ratios") List<Integer> ratios) {
        List<Money> parts = total.allocate(toArray(ratios));

        assertThat(parts.stream().reduce(Money.zero(CurrencyCode.HNL), Money::add)).isEqualTo(total);
        assertThat(parts).allSatisfy(part -> assertThat(part.isNegative()).isFalse());
    }

    @Property(tries = TRIES)
    void noPartIsPositiveWhenTheTotalIsNotPositive(
            @ForAll("nonPositiveAmounts") Money total, @ForAll("ratios") List<Integer> ratios) {
        List<Money> parts = total.allocate(toArray(ratios));

        assertThat(parts).allSatisfy(part -> assertThat(part.isPositive()).isFalse());
    }

    @Property(tries = TRIES)
    void everyPartIsWithinOneMinorUnitOfItsExactShare(
            @ForAll("nonNegativeAmounts") Money total, @ForAll("ratios") List<Integer> ratios) {
        int[] weights = toArray(ratios);
        List<Money> parts = total.allocate(weights);
        long weightSum = ratios.stream().mapToLong(Integer::longValue).sum();
        BigDecimal oneMinorUnit = BigDecimal.ONE.movePointLeft(CurrencyCode.HNL.minorUnitDigits());

        for (int i = 0; i < parts.size(); i++) {
            BigDecimal exactShare = total.amount()
                    .multiply(BigDecimal.valueOf(weights[i]))
                    .divide(BigDecimal.valueOf(weightSum), 10, RoundingMode.HALF_UP);
            BigDecimal distance = parts.get(i).amount().subtract(exactShare).abs();
            assertThat(distance).isLessThan(oneMinorUnit);
        }
    }

    @Property(tries = TRIES)
    void addSubtractNegateAndMultiplyByLongMatchAFullScaleBigDecimalCalculation(
            @ForAll("boundedAmounts") Money a, @ForAll("boundedAmounts") Money b,
            @ForAll("boundedAmounts") Money c, @ForAll @IntRange(min = -5, max = 5) int factor) {
        BigDecimal exact = a.amount().add(b.amount()).subtract(c.amount())
                .negate().multiply(BigDecimal.valueOf(factor));
        Money viaMoney = a.add(b).subtract(c).negate().multiply(factor);

        assertThat(viaMoney.amount()).isEqualByComparingTo(exact);
        assertThat(viaMoney.roundToMinorUnit(RoundingMode.HALF_UP).amount())
                .isEqualByComparingTo(exact.setScale(CurrencyCode.HNL.minorUnitDigits(), RoundingMode.HALF_UP));
    }

    @Property(tries = TRIES)
    void percentageMatchesTheExactProductRoundedOnce(
            @ForAll("boundedAmounts") Money base, @ForAll("percentages") Percentage percentage) {
        BigDecimal exact = base.amount().multiply(percentage.value()).movePointLeft(2)
                .setScale(Money.SCALE, RoundingMode.HALF_UP);

        Money result = base.percentage(percentage, RoundingMode.HALF_UP);

        assertThat(result.amount()).isEqualByComparingTo(exact);
    }

    private static int[] toArray(List<Integer> ratios) {
        return ratios.stream().mapToInt(Integer::intValue).toArray();
    }

    @Provide
    Arbitrary<Money> nonNegativeAmounts() {
        return Arbitraries.longs().between(0, MAX_MINOR_UNITS)
                .map(cents -> Money.of(BigDecimal.valueOf(cents, 2), CurrencyCode.HNL));
    }

    @Provide
    Arbitrary<Money> nonPositiveAmounts() {
        return Arbitraries.longs().between(-MAX_MINOR_UNITS, 0)
                .map(cents -> Money.of(BigDecimal.valueOf(cents, 2), CurrencyCode.HNL));
    }

    @Provide
    Arbitrary<Money> boundedAmounts() {
        return Arbitraries.longs().between(-10_000_000_00L, 10_000_000_00L)
                .map(cents -> Money.of(BigDecimal.valueOf(cents, 2), CurrencyCode.HNL));
    }

    @Provide
    Arbitrary<List<Integer>> ratios() {
        return Arbitraries.integers().between(1, 1000).list().ofMinSize(1).ofMaxSize(20);
    }

    @Provide
    Arbitrary<Percentage> percentages() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("100")).ofScale(4)
                .map(Percentage::of);
    }
}
