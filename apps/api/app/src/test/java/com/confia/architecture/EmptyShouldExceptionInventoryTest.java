package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;

/**
 * ADR-0018, mechanism (a), "Inventario de caducidad", extended by ADR-0020 §2 with a second marker
 * type: every {@code allowEmptyShould(true)} or {@code optionalLayer(...)} exception under {@code
 * com.confia.architecture} is declared here with the condition that justifies it and the ADR that
 * authorizes it. This test fails, naming the rule, the day that condition stops holding — the
 * signal that the exception must be removed from that rule together with its entry here, never
 * left in place once its justification is gone.
 *
 * <p>{@link SuppressionCitesAdrTest} keeps this list and the number of {@code allowEmptyShould(}
 * and {@code optionalLayer(} call sites in the codebase equal to the count of each marker type
 * here, so an exception cannot be added to a rule without also being declared here.
 */
class EmptyShouldExceptionInventoryTest {

    /** Which suppression mechanism an {@link ExpiringException} entry declares (ADR-0018, ADR-0020). */
    enum Marker {
        ALLOW_EMPTY_SHOULD,
        OPTIONAL_LAYER
    }

    /**
     * Package-visible for {@link SuppressionCitesAdrTest}'s count check. <b>One entry today</b>:
     * the production half of {@code WebExposedTypesRuleTest}'s controller signature rule (rule
     * W2a, web-edge-foundations design.md, decision 20) evaluates against no class at all, because
     * no production class is a controller yet. Its owner is {@code session-tokens-and-web-layer},
     * whose first production endpoint ends the condition, and the build fails that day until the
     * exception is removed from the rule together with this entry (ADR-0018, section 2).
     *
     * <p>History: the last {@link Marker#OPTIONAL_LAYER} exception, on {@code
     * LayeredArchitectureTest.productionLayeringRule}, layer {@code Web} (ADR-0020 §2), expired in F0
     * change 3 (frontend-monorepo-and-contracts-pipeline, task 1.3), when {@link
     * com.confia.shared.web.openapi.ContractSchemas} became the first production class in a {@code
     * web} package; it was retired together with {@code optionalLayer("Web")} itself, by the owner's
     * decision of 2026-09-30. Before it, the {@code Infrastructure} entry retired in PR A2, F0
     * change 5, when {@link com.confia.organization.infrastructure.JooqInstitutionRepository} became
     * the first production class in an {@code infrastructure} package (ADR-0020, alcance punto 3),
     * and ADR-0018's own {@code allowEmptyShould(true)} exception on {@code
     * LayeredArchitectureTest.productionCodeRespectsLayeringYet} expired the moment {@code
     * organization.domain} added its first production class (change 4). The mechanism itself stays
     * in place for whichever future exception needs it.
     */
    static final List<ExpiringException> EXCEPTIONS = List.of(
            new ExpiringException(Marker.ALLOW_EMPTY_SHOULD,
                    "WebExposedTypesRuleTest.productionControllersExposeNoDomainOrPersistenceType"
                            + " (rule W2a, the public signature of controllers)",
                    "ADR-0018",
                    "no production class is meta-annotated with @Controller (owner: "
                            + "session-tokens-and-web-layer, whose first production endpoint "
                            + "ends this exception)",
                    production -> production.stream()
                            .noneMatch(javaClass -> javaClass.isMetaAnnotatedWith(Controller.class))));

    @Test
    void everyExceptionsConditionStillHolds() {
        JavaClasses production = productionClasses();
        for (ExpiringException exception : EXCEPTIONS) {
            assertThat(exception.stillJustified().test(production))
                    .as("%s's %s exception no longer holds (%s). Remove the suppression marker "
                            + "from that rule and this inventory entry, or update the condition "
                            + "and cite the ADR that extends it (ADR-0018, section 2; ADR-0020).",
                            exception.rule(), exception.adr(), exception.condition())
                    .isTrue();
        }
    }

    /**
     * The controller condition is a real check: it holds over production today and stops holding as
     * soon as a class is a controller, which the permanent fixture of rule W2a is. Without this,
     * an expiry predicate that always answered {@code true} would pass the test above forever.
     */
    @Test
    void theControllerConditionStopsHoldingWhenAControllerExists() {
        ExpiringException controllers = EXCEPTIONS.stream()
                .filter(exception -> exception.marker() == Marker.ALLOW_EMPTY_SHOULD)
                .findFirst().orElseThrow();

        assertThat(controllers.stillJustified().test(productionClasses())).isTrue();
        assertThat(controllers.stillJustified().test(fixtureClasses())).isFalse();
    }

    /** Package-visible for {@link SuppressionCitesAdrTest}'s per-marker count check. */
    static long countOf(Marker marker) {
        return EXCEPTIONS.stream().filter(exception -> exception.marker() == marker).count();
    }

    /**
     * One declared exception: which suppression mechanism it uses, the rule that carries it, the
     * ADR that authorizes it, the plain-language condition a human reviews (ADR-0018, "revisión
     * humana, declarada como tal"), and the executable check of that same condition against
     * production classes.
     */
    record ExpiringException(Marker marker, String rule, String adr, String condition,
            Predicate<JavaClasses> stillJustified) {
    }
}
