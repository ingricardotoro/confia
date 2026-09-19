package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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

    @Test
    void productionCodeNeverConstructsBigDecimalFromFloatingPoint() {
        NO_BIG_DECIMAL_FROM_FLOATING_POINT.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureFloatingPointBigDecimalConstruction() {
        assertRuleRejects(NO_BIG_DECIMAL_FROM_FLOATING_POINT, fixtureClasses(),
                "FloatingPointBigDecimal");
    }
}
