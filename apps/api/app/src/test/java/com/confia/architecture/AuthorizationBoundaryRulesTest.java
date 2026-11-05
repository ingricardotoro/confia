package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.StaffRole;
import com.confia.kernel.Permission;
import com.confia.shared.security.PermissionResolver;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Where the permission decision may live and what may depend on it (rbac-permission-matrix-and-audit-
 * integration design.md D1, D6 and D18; scenarios BI131 and BI133): the decision is taken by the
 * chain, so no class carries a method-level permission annotation or enables method security, and no
 * class of an {@code application} package depends on the decider or on its exception. The catalog and
 * the port import no module, and the domain of {@code identity} takes the catalog from {@code kernel}.
 * Each rule has the permanent fixture it must reject, so it is shown to find what it looks for.
 */
class AuthorizationBoundaryRulesTest {

    private static final Set<String> PERMISSION_ANNOTATIONS = Set.of("PreAuthorize",
            "PostAuthorize", "Secured", "RolesAllowed", "RequirePermission", "EnableMethodSecurity");
    private static final String DECIDER = "com.confia.shared.web.authorization.PermissionDecider";
    private static final String DENIED =
            "com.confia.shared.web.authorization.PermissionDeniedException";

    private static final DescribedPredicate<JavaAnnotation<?>> A_PERMISSION_ANNOTATION =
            describe("a method-level permission annotation", annotation ->
                    PERMISSION_ANNOTATIONS.contains(annotation.getRawType().getSimpleName()));

    static ArchRule noClassEnablesMethodSecurityOrIsAnnotatedWithAPermission() {
        return classes().should().notBeAnnotatedWith(A_PERMISSION_ANNOTATION)
                .because("the chain decides before any interceptor or transaction exists; a "
                        + "method-level annotation would decide after both (design.md D6)");
    }

    static ArchRule noMethodCarriesAPermissionAnnotation() {
        return methods().should().notBeAnnotatedWith(A_PERMISSION_ANNOTATION)
                .because("the chain decides before any interceptor or transaction exists (D6)");
    }

    static ArchRule applicationNeverDependsOnTheDecider(String applicationPackage) {
        return noClasses().that().resideInAPackage(applicationPackage)
                .should().dependOnClassesThat().haveFullyQualifiedName(DECIDER)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(DENIED)
                .because("a use case never decides a permission: the filter chain did it before "
                        + "the controller ran (design.md D1)");
    }

    @Test
    void productionCarriesNoPermissionAnnotationAndEnablesNoMethodSecurity() {
        noClassEnablesMethodSecurityOrIsAnnotatedWithAPermission().check(productionClasses());
        noMethodCarriesAPermissionAnnotation().check(productionClasses());
    }

    @Test
    void rejectsTheFixtureThatEnablesMethodSecurityAndGuardsOperations() {
        assertRuleRejects(noClassEnablesMethodSecurityOrIsAnnotatedWithAPermission(),
                fixtureClasses(), "BadMethodLevelPermissions");
        assertRuleRejects(noMethodCarriesAPermissionAnnotation(), fixtureClasses(),
                "BadMethodLevelPermissions.expression", "BadMethodLevelPermissions.declared");
    }

    @Test
    void noApplicationClassDependsOnTheDeciderOrItsException() {
        applicationNeverDependsOnTheDecider("..application..").check(productionClasses());
    }

    @Test
    void rejectsTheFixtureApplicationClassThatDependsOnThem() {
        assertRuleRejects(applicationNeverDependsOnTheDecider("..application.."), fixtureClasses(),
                "BadApplicationUsesTheDecider");
    }

    @Test
    void theCatalogAndThePortImportNoModuleAndTheIdentityDomainTakesTheCatalogFromKernel() {
        JavaClass catalog = productionClasses().get(Permission.class);
        JavaClass port = productionClasses().get(PermissionResolver.class);
        JavaClass role = productionClasses().get(StaffRole.class);

        assertThat(catalog.getDirectDependenciesFromSelf()).as("the scan sees dependencies")
                .isNotEmpty();
        assertThat(port.getDirectDependenciesFromSelf()).isNotEmpty();
        assertThat(role.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getName()))
                .contains("com.confia.kernel.Permission");
        noClasses().that().haveFullyQualifiedName(Permission.class.getName())
                .or().haveFullyQualifiedName(PermissionResolver.class.getName())
                .should().dependOnClassesThat(resideInAnyPackage("com.confia.identity..",
                        "com.confia.organization..", "com.confia.shared.web.."))
                .check(productionClasses());
        noClasses().that().resideInAPackage("com.confia.identity.domain..")
                .should().dependOnClassesThat().resideInAPackage("com.confia.shared..")
                .check(productionClasses());
    }
}
