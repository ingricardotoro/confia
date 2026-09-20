package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

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
     * Package-visible for {@link SuppressionCitesAdrTest}'s count check. Two entries, both
     * {@link Marker#OPTIONAL_LAYER} (ADR-0020 §2): {@code organization} has no production class
     * yet in an {@code infrastructure} package (change 5 adds the first jOOQ adapter) or in a
     * {@code web} package (the first business controller adds one). ADR-0018's own
     * {@code allowEmptyShould(true)} exception on
     * {@code LayeredArchitectureTest.productionCodeRespectsLayeringYet} expired the moment {@code
     * organization.domain} added its first production class (change 4); the mechanism itself
     * stays in place for whichever future exception needs it.
     */
    static final List<ExpiringException> EXCEPTIONS = List.of(
            new ExpiringException(Marker.OPTIONAL_LAYER,
                    "LayeredArchitectureTest.productionLayeringRule, layer Infrastructure",
                    "ADR-0020",
                    "no production class resides in an infrastructure package yet (change 5 adds "
                            + "the first jOOQ adapter)",
                    classes -> LayeredArchitectureTest.noProductionClassInLayer(classes,
                            "infrastructure")),
            new ExpiringException(Marker.OPTIONAL_LAYER,
                    "LayeredArchitectureTest.productionLayeringRule, layer Web", "ADR-0020",
                    "no production class resides in a web package yet (the first business "
                            + "controller adds one)",
                    classes -> LayeredArchitectureTest.noProductionClassInLayer(classes, "web")));

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
