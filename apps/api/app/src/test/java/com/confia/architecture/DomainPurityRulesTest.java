package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Scenario BI132 (rbac-permission-matrix-and-audit-integration, design.md decisions D2 and D18): the
 * catalog of permissions and the matrix of roles are code that no framework may touch. Two rules,
 * each checked twice, once on the real classes and once on a permanent fixture that breaks it
 * (ADR-0018):
 *
 * <ul>
 *   <li>a class in a {@code ..domain..} package depends, inside {@code com.confia}, only on the
 *       {@code kernel} and on its own module, and never on Spring, jOOQ or the generated jOOQ code;
 *   <li>{@code com.confia.kernel.Permission} depends on nothing outside the JDK.
 * </ul>
 *
 * <p>{@code NoCrossModuleDomainImportsTest} already keeps one module's domain from importing
 * another's; this rule is wider (a domain may not reach the {@code shared} module, for example, whose
 * port for permissions would otherwise let a business rule read from the web edge) and adds the
 * framework half.
 */
class DomainPurityRulesTest {

    private static final String PRODUCTION_ROOT = "com.confia";
    private static final String FIXTURE_ROOT = "com.confia.architecture.fixture.domainpurity";
    private static final String PERMISSION = "com.confia.kernel.Permission";
    private static final String FIXTURE_PERMISSION = FIXTURE_ROOT + ".kernel.BadKernelPermission";
    private static final List<String> FRAMEWORK_PACKAGES = List.of("org.springframework", "org.jooq", "confia.generated");

    static ArchRule domainDependsOnlyOnKernelAndItsOwnModule(String root) {
        return classes()
                .that().resideInAPackage(root + "..")
                .and().resideInAPackage("..domain..")
                .should(onlyDependOnTheKernelTheOwnModuleAndNoFramework(root))
                .because("the domain layer is pure: inside the base package it sees the kernel and its own "
                        + "module, and it never names Spring or jOOQ (docs/01 and ADR-0002)");
    }

    static ArchRule permissionDependsOnNothingOutsideTheJdk(String fullName) {
        return classes()
                .that().haveFullyQualifiedName(fullName)
                .should(onlyDependOnTheJdk())
                .because("the catalog of permissions is a base type that every layer may use, so it carries "
                        + "no dependency (design.md, decision D2)");
    }

    @Test
    void productionDomainDependsOnlyOnTheKernelAndItsOwnModule() {
        domainDependsOnlyOnKernelAndItsOwnModule(PRODUCTION_ROOT).check(productionClasses());
    }

    @Test
    void theRealDomainSetIsNotEmptyAndHoldsStaffAccount() {
        assertThat(productionClasses().stream()
                .filter(javaClass -> javaClass.getPackageName().equals("com.confia.identity.domain"))
                .map(JavaClass::getSimpleName))
                .contains("StaffAccount");
    }

    @Test
    void rejectsTheFixtureDomainsThatNameSpringJooqOrAnotherModule() {
        assertRuleRejects(domainDependsOnlyOnKernelAndItsOwnModule(FIXTURE_ROOT), fixtureClasses(),
                "BadDomainUsesSpring", "org.springframework", "BadDomainUsesJooq", "org.jooq",
                "BadDomainUsesAnotherModule", "SomeOtherModuleType");
    }

    @Test
    void thePermissionCatalogDependsOnNothingOutsideTheJdk() {
        permissionDependsOnNothingOutsideTheJdk(PERMISSION).check(productionClasses());
    }

    @Test
    void rejectsAFixtureCatalogThatDependsOnSpring() {
        assertRuleRejects(permissionDependsOnNothingOutsideTheJdk(FIXTURE_PERMISSION), fixtureClasses(),
                "BadKernelPermission", "org.springframework.stereotype.Component");
    }

    private static ArchCondition<JavaClass> onlyDependOnTheKernelTheOwnModuleAndNoFramework(String root) {
        return new ArchCondition<>("depend only on the kernel and its own module, and on no framework") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String ownModule = moduleOf(javaClass).orElseThrow();
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    boolean framework = FRAMEWORK_PACKAGES.stream().anyMatch(target::startsWith);
                    boolean insideRoot = target.startsWith(root + ".");
                    boolean allowedInside = target.startsWith(root + ".kernel") || target.startsWith(ownModule);
                    if (framework || (insideRoot && !allowedInside)) {
                        events.add(SimpleConditionEvent.violated(javaClass, String.format(
                                "%s depends on %s, which is neither the kernel nor module '%s'",
                                javaClass.getFullName(), dependency.getTargetClass().getFullName(), ownModule)));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> onlyDependOnTheJdk() {
        return new ArchCondition<>("depend on nothing outside the JDK") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    if (!target.getPackageName().startsWith("java.") && !target.equals(javaClass)) {
                        events.add(SimpleConditionEvent.violated(javaClass, String.format(
                                "%s depends on %s, which is not part of the JDK",
                                javaClass.getFullName(), target.getFullName())));
                    }
                }
            }
        };
    }

    /** The package prefix strictly before the first {@code domain} segment. */
    private static Optional<String> moduleOf(JavaClass javaClass) {
        String[] segments = javaClass.getPackageName().split("\\.");
        int domainIndex = Arrays.asList(segments).indexOf("domain");
        return domainIndex < 0 ? Optional.empty()
                : Optional.of(String.join(".", Arrays.copyOfRange(segments, 0, domainIndex)));
    }
}
