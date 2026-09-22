package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ADR-0015 compliance 4, design.md decision 9 rule R2: a class of {@code com.confia.<módulo>..}
 * must not depend on a generated jOOQ table type whose simple name does not start with the
 * capitalized name of that same module. Only verifiable because {@code
 * globalObjectReferences=false} (task 1.7) makes every table reference go through its own
 * generated type instead of a shared umbrella class.
 */
class TableOwnershipByModuleTest {

    /** Same package-layer segments {@link NoCrossModuleDomainImportsTest} anchors on for its own
     * "domain" case, generalized here to every layer so the module of a class in {@code
     * infrastructure} (or any other layer) can be extracted the same way (design.md decision 9,
     * rule R2). */
    private static final Set<String> LAYER_SEGMENTS =
            Set.of("domain", "application", "infrastructure", "web");

    private static final String GENERATED_TABLES_PACKAGE_PREFIX = "confia.generated.jooq.tables";

    private static final ArchRule RULE = classes()
            .should(onlyUseGeneratedTableTypesOfItsOwnModule())
            .because("a module must only use the generated jOOQ table types of its own tables "
                    + "(ADR-0015, regla 3)");

    @Test
    void productionCodeOnlyUsesItsOwnModulesGeneratedTables() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureForeignTableUsage() {
        assertRuleRejects(RULE, fixtureClasses(), "BadForeignTableUser");
    }

    private static ArchCondition<JavaClass> onlyUseGeneratedTableTypesOfItsOwnModule() {
        return new ArchCondition<>("only use generated jOOQ table types of its own module") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Optional<String> ownModule = moduleOf(javaClass);
                if (ownModule.isEmpty()) {
                    return;
                }
                String expectedPrefix = capitalize(ownModule.get());
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (!target.getPackageName().startsWith(GENERATED_TABLES_PACKAGE_PREFIX)) {
                        continue;
                    }
                    if (!target.getSimpleName().startsWith(expectedPrefix)) {
                        String message = String.format(
                                "%s (module '%s') depends on %s, a generated table type of "
                                        + "another module (expected a simple name starting with "
                                        + "'%s')",
                                javaClass.getFullName(), ownModule.get(), target.getFullName(),
                                expectedPrefix);
                        events.add(SimpleConditionEvent.violated(javaClass, message));
                    }
                }
            }
        };
    }

    /**
     * The package prefix strictly before the first layer segment ({@code domain}, {@code
     * application}, {@code infrastructure} or {@code web}), or empty if the class does not reside
     * in any of the four layer packages. Mirrors {@code NoCrossModuleDomainImportsTest}'s
     * extraction, generalized to every layer instead of only {@code domain} (design.md decision 9,
     * rule R2: production's {@code com.confia.organization.infrastructure} yields module {@code
     * organization}; the fixture's {@code ...fixture.billing.infrastructure} yields module {@code
     * billing}).
     */
    private static Optional<String> moduleOf(JavaClass javaClass) {
        String[] segments = javaClass.getPackageName().split("\\.");
        for (int i = 1; i < segments.length; i++) {
            if (LAYER_SEGMENTS.contains(segments[i])) {
                return Optional.of(segments[i - 1]);
            }
        }
        return Optional.empty();
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
