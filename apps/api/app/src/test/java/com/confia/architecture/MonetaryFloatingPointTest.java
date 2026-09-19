package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.Money;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.math.BigDecimal;
import java.math.MathContext;
import org.junit.jupiter.api.Test;

/**
 * ADR-0004 §Cumplimiento 2, five ArchUnit rules that keep floating-point primitives out of every
 * monetary construction and type (CLAUDE.md, rule 1). Each rule follows the same two-half proof as
 * every other rule in this package: production code passes without an empty-set exception, and a
 * permanent fixture under {@code com.confia.architecture.fixture.monetary} is rejected by name
 * (design.md, decision 3).
 */
class MonetaryFloatingPointTest {

    /**
     * Rule 1 (design.md, decision 3): forbids the three ways a {@link BigDecimal} can be built
     * straight from a binary {@code double}, which has already rounded the value before {@code
     * Money} ever sees it (CLAUDE.md, rule 1). Selection is by exact overload, not by name: {@code
     * com.confia.kernel.Money#multiply(long)} calls {@code BigDecimal.valueOf(long)}, a different
     * overload, and must keep passing.
     */
    private static final ArchRule NO_BIG_DECIMAL_FROM_FLOATING_POINT = noClasses()
            .should().callConstructor(BigDecimal.class, double.class)
            .orShould().callConstructor(BigDecimal.class, double.class, MathContext.class)
            .orShould().callMethod(BigDecimal.class, "valueOf", double.class)
            .because("ADR-0004 §Cumplimiento 2 forbids constructing a BigDecimal from a double: "
                    + "new BigDecimal(double), new BigDecimal(double, MathContext) and "
                    + "BigDecimal.valueOf(double) all round the binary floating-point value before "
                    + "any monetary type ever sees it (CLAUDE.md, rule 1)");

    /**
     * Rule 2 (design.md, decision 3): forbids {@link BigDecimal#equals(Object)} outside {@link
     * Money} (and any of its nested classes, none exist today): it also compares scale, so {@code
     * 1.0} and {@code 1.00} come out unequal (CLAUDE.md, rule 1).
     */
    private static final DescribedPredicate<JavaClass> NOT_MONEY =
            DescribedPredicate.not(belongToAnyOf(Money.class));

    private static final ArchRule NO_BIG_DECIMAL_EQUALS_OUTSIDE_MONEY = noClasses()
            .that(NOT_MONEY)
            .should().callMethod(BigDecimal.class, "equals", Object.class)
            .because("ADR-0004 §Cumplimiento 2 forbids BigDecimal.equals(Object) outside Money: it "
                    + "also compares scale, so 1.0 and 1.00 come out unequal (CLAUDE.md, rule 1)");

    @Test
    void productionCodeNeverConstructsBigDecimalFromFloatingPoint() {
        NO_BIG_DECIMAL_FROM_FLOATING_POINT.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureFloatingPointBigDecimalConstruction() {
        assertRuleRejects(NO_BIG_DECIMAL_FROM_FLOATING_POINT, fixtureClasses(),
                "FloatingPointBigDecimal");
    }

    @Test
    void productionCodeNeverCallsBigDecimalEqualsOutsideMoney() {
        NO_BIG_DECIMAL_EQUALS_OUTSIDE_MONEY.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureRawBigDecimalComparison() {
        assertRuleRejects(NO_BIG_DECIMAL_EQUALS_OUTSIDE_MONEY, fixtureClasses(),
                "RawBigDecimalComparison");
    }

    @Test
    void moneyIsExcludedFromTheEqualsRuleSelection() {
        assertThat(NOT_MONEY.test(productionClasses().get(Money.class))).isFalse();
    }
}
