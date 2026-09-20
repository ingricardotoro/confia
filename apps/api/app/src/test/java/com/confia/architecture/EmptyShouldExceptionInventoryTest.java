package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * ADR-0018, mechanism (a), "Inventario de caducidad": every {@code allowEmptyShould(true)}
 * exception under {@code com.confia.architecture} is declared here with the condition that
 * justifies it and the ADR that authorizes it. This test fails, naming the rule, the day that
 * condition stops holding — the signal that {@code allowEmptyShould(true)} must be removed from
 * that rule together with its entry here, never left in place once its justification is gone.
 *
 * <p>{@link SuppressionCitesAdrTest} keeps this list and the number of {@code allowEmptyShould(}
 * call sites in the codebase equal, so an exception cannot be added to a rule without also being
 * declared here.
 */
class EmptyShouldExceptionInventoryTest {

    /**
     * Package-visible for {@link SuppressionCitesAdrTest}'s count check. Empty since ADR-0018's
     * exception on {@code LayeredArchitectureTest.productionCodeRespectsLayeringYet} expired the
     * moment {@code organization.domain} added its first production class (change 4); the
     * mechanism itself stays in place for whichever future exception needs it.
     */
    static final List<ExpiringException> EXCEPTIONS = List.of();

    @Test
    void everyExceptionsConditionStillHolds() {
        JavaClasses production = productionClasses();
        for (ExpiringException exception : EXCEPTIONS) {
            assertThat(exception.stillJustified().test(production))
                    .as("%s's %s exception no longer holds (%s). Remove allowEmptyShould(true) "
                            + "from that rule and this inventory entry, or update the condition "
                            + "and cite the ADR that extends it (ADR-0018, section 2).",
                            exception.rule(), exception.adr(), exception.condition())
                    .isTrue();
        }
    }

    /**
     * One declared exception: the rule that carries it, the ADR that authorizes it, the plain-
     * language condition a human reviews (ADR-0018, "revisión humana, declarada como tal"), and
     * the executable check of that same condition against production classes.
     */
    record ExpiringException(String rule, String adr, String condition,
            Predicate<JavaClasses> stillJustified) {
    }
}
