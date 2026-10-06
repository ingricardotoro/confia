package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Rule W1 (web-edge-foundations design.md, decision 20; specs/build-integrity, requirement "Reglas
 * de dependencia de la capa {@code web} de producción"): no class of a {@code ..web..} package
 * depends on an {@code ..infrastructure..} package, on the generated jOOQ package or on {@code
 * org.jooq} itself. A web class talks to a use case, never to the database layer (ADR-0002) and
 * never to jOOQ (ADR-0015, rule 4).
 *
 * <p>Both halves of the repository's two-half convention (ADR-0018). The production half evaluates
 * the real classes of {@code com.confia.shared.web} and carries no empty-set exception: it is never
 * empty, and a scope that stopped matching them would fail the build instead of passing. The
 * fixture half must reject {@code BadWebUsesInfrastructure} for each of the three prohibited
 * targets, naming the class and the target.
 *
 * <p>{@link LayeredArchitectureTest} overlaps with this rule for the {@code infrastructure} target
 * only. This rule exists because the specification asks for one rule with its own fixture per
 * prohibition, and because the layered rule says nothing about {@code org.jooq}.
 */
class WebLayerDependencyRulesTest {

    private static final ArchRule RULE = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..infrastructure..", "confia.generated.jooq..", "org.jooq..")
            .because("a web class reaches the system only through an application use case; "
                    + "infrastructure and jOOQ are confined behind it (ADR-0002, ADR-0015 "
                    + "rule 4)");

    @Test
    void productionWebClassesNeverDependOnInfrastructureOrJooq() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureWebClassThatReachesInfrastructureAndJooq() {
        assertRuleRejects(RULE, fixtureClasses(), "BadWebUsesInfrastructure",
                "SomeInfrastructureType", "org.jooq.DSLContext", "confia.generated.jooq.Public");
    }
}
