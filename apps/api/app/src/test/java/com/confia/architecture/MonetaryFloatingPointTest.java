package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.Money;
import com.confia.kernel.Percentage;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
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

    /**
     * A monetary type (design.md, decision 3): {@link Money}, {@link Percentage}, or any class
     * that declares a field whose raw type is one of those two.
     */
    private static final DescribedPredicate<JavaClass> MONETARY_TYPE = DescribedPredicate.describe(
            "Money, Percentage, or a class declaring a Money or Percentage field",
            MonetaryFloatingPointTest::isMonetaryType);

    /** A floating-point primitive or wrapper type: {@code double}, {@code float}, or their boxes. */
    private static final DescribedPredicate<JavaClass> FLOATING_POINT_TYPE = DescribedPredicate.describe(
            "double, float, Double or Float",
            javaClass -> javaClass.isEquivalentTo(double.class)
                    || javaClass.isEquivalentTo(float.class)
                    || javaClass.isEquivalentTo(Double.class)
                    || javaClass.isEquivalentTo(Float.class));

    private static boolean isMonetaryType(JavaClass javaClass) {
        return isMoneyOrPercentage(javaClass) || declaresAMoneyOrPercentageField(javaClass);
    }

    private static boolean isMoneyOrPercentage(JavaClass javaClass) {
        return javaClass.isEquivalentTo(Money.class) || javaClass.isEquivalentTo(Percentage.class);
    }

    private static boolean declaresAMoneyOrPercentageField(JavaClass javaClass) {
        return javaClass.getFields().stream()
                .anyMatch(field -> isMoneyOrPercentage(field.getRawType()));
    }

    /** Rule 3 (design.md, decision 3): no floating-point field in a monetary type. */
    private static final ArchRule NO_FLOATING_POINT_FIELDS_IN_MONETARY_TYPES = noFields()
            .that().areDeclaredInClassesThat(MONETARY_TYPE)
            .should().haveRawType(FLOATING_POINT_TYPE)
            .because("ADR-0004 §Cumplimiento 2 forbids a double or float field in a monetary type "
                    + "(CLAUDE.md, rule 1)");

    /** Rule 4 (design.md, decision 3): no floating-point return type in a monetary type. */
    private static final ArchRule NO_FLOATING_POINT_RETURNS_IN_MONETARY_TYPES = noMethods()
            .that().areDeclaredInClassesThat(MONETARY_TYPE)
            .should().haveRawReturnType(FLOATING_POINT_TYPE)
            .because("ADR-0004 §Cumplimiento 2 forbids a double or float return type in a monetary "
                    + "type (CLAUDE.md, rule 1)");

    /** A parameter list containing at least one floating-point type. */
    private static final DescribedPredicate<List<JavaClass>> HAS_A_FLOATING_POINT_PARAMETER =
            DescribedPredicate.describe("a double, float, Double or Float parameter",
                    (List<JavaClass> parameterTypes) ->
                            parameterTypes.stream().anyMatch(FLOATING_POINT_TYPE::test));

    /** Rule 5 (design.md, decision 3): no floating-point parameter in a monetary type. */
    private static final ArchRule NO_FLOATING_POINT_PARAMETERS_IN_MONETARY_TYPES = noCodeUnits()
            .that().areDeclaredInClassesThat(MONETARY_TYPE)
            .should().haveRawParameterTypes(HAS_A_FLOATING_POINT_PARAMETER)
            .because("ADR-0004 §Cumplimiento 2 forbids a double or float parameter in a "
                    + "constructor or method of a monetary type (CLAUDE.md, rule 1)");

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

    @Test
    void productionImportIncludesTheKernelMonetaryTypes() {
        assertThat(productionClasses().contain(Money.class)).isTrue();
        assertThat(productionClasses().contain(Percentage.class)).isTrue();
    }

    @Test
    void productionCodeNeverDeclaresAFloatingPointFieldInAMonetaryType() {
        NO_FLOATING_POINT_FIELDS_IN_MONETARY_TYPES.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureFloatingPointField() {
        assertRuleRejects(NO_FLOATING_POINT_FIELDS_IN_MONETARY_TYPES, fixtureClasses(),
                "FloatingPointPriceTag");
    }

    @Test
    void productionCodeNeverDeclaresAFloatingPointReturnInAMonetaryType() {
        NO_FLOATING_POINT_RETURNS_IN_MONETARY_TYPES.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureFloatingPointReturn() {
        assertRuleRejects(NO_FLOATING_POINT_RETURNS_IN_MONETARY_TYPES, fixtureClasses(),
                "FloatingPointPriceTag");
    }

    @Test
    void productionCodeNeverDeclaresAFloatingPointParameterInAMonetaryType() {
        NO_FLOATING_POINT_PARAMETERS_IN_MONETARY_TYPES.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureFloatingPointParameter() {
        assertRuleRejects(NO_FLOATING_POINT_PARAMETERS_IN_MONETARY_TYPES, fixtureClasses(),
                "FloatingPointPriceTag");
    }
}
