package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.AccountPermissions;
import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.PermissionResolver;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The permission resolver port has one production adapter, in {@code identity}, and no adapter hides in
 * a lambda (rbac-permission-matrix-and-audit-integration design.md D5; scenario I284). Same method as
 * the inventory of the session validity port: a static scan of the compiled production tree over a set
 * proven non-empty first, and a deliberately violating class for each absence, so the detector is shown
 * to find what it looks for. The only production consumer of the port is the permission decider of the
 * administrative chain, which arrives in task 3 of the change; until then nobody calls it, and that
 * task turns the empty expectation below into exactly that one class.
 */
class PermissionResolverInventoryTest {

    private static JavaClasses production() {
        JavaClasses classes = productionClasses();
        assertThat(classes.stream().map(JavaClass::getFullName).toList())
                .as("the scanned tree is populated, or every absence below would pass vacuously")
                .contains("com.confia.shared.security.PermissionResolver",
                        "com.confia.identity.application.AccountPermissions",
                        "com.confia.shared.security.SessionValidity");
        return classes;
    }

    @Test
    void thePortIsAnInterfaceOfSharedThatDoesNotDependOnIdentity() {
        JavaClass port = production().get(PermissionResolver.class);

        assertThat(port.isInterface()).isTrue();
        assertThat(port.getPackageName()).isEqualTo("com.confia.shared.security");
        assertThat(port.getDirectDependenciesFromSelf())
                .as("it has dependencies of its own, so the identity check passes over something")
                .isNotEmpty();
        assertThat(port.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getFullName())
                .filter(name -> name.startsWith("com.confia.identity"))).isEmpty();
    }

    @Test
    void thePortHasExactlyOneProductionAdapterAndItBelongsToIdentity() {
        assertThat(implementationsOf(production())).containsExactly(AccountPermissions.class.getName());
        assertThat(adapterViolations(production())).isEmpty();
    }

    @Test
    void theAdapterDetectorFindsASecondAdapterAndOneOutsideIdentity() {
        JavaClasses withASecondOne = new ClassFileImporter()
                .importClasses(AccountPermissions.class, SecondAdapter.class);
        JavaClasses outsideIdentity = new ClassFileImporter().importClasses(SecondAdapter.class);

        assertThat(implementationsOf(withASecondOne)).hasSize(2);
        assertThat(adapterViolations(withASecondOne)).anyMatch(text -> text.contains("exactly one"));
        assertThat(adapterViolations(outsideIdentity))
                .anyMatch(text -> text.contains("outside com.confia.identity"));
    }

    @Test
    void noProductionMethodReturnsThePortSoNoAdapterHidesInALambda() {
        assertThat(classesReturningThePort(production())).isEmpty();
        assertThat(classesReturningThePort(new ClassFileImporter().importClasses(LambdaFactory.class)))
                .containsExactly(LambdaFactory.class.getName());
    }

    @Test
    void noProductionClassCallsThePortYetAndTheDetectorFindsOneThatDoes() {
        assertThat(classesCallingThePort(production()))
                .as("the decider of task 3 will be the only entry").isEmpty();
        assertThat(classesCallingThePort(new ClassFileImporter().importClasses(Consumer.class)))
                .containsExactly(Consumer.class.getName());
    }

    private static List<String> implementationsOf(JavaClasses classes) {
        return classes.stream().filter(javaClass -> !javaClass.isInterface())
                .filter(javaClass -> javaClass.isAssignableTo(PermissionResolver.class))
                .map(JavaClass::getName).sorted().toList();
    }

    private static List<String> adapterViolations(JavaClasses classes) {
        List<String> adapters = implementationsOf(classes);
        List<String> violations = new ArrayList<>();
        if (adapters.size() != 1) {
            violations.add("expected exactly one adapter of the port and found " + adapters);
        }
        adapters.stream().filter(name -> !name.startsWith("com.confia.identity."))
                .forEach(name -> violations.add(name + " is outside com.confia.identity"));
        return violations;
    }

    private static List<String> classesReturningThePort(JavaClasses classes) {
        return classes.stream().filter(javaClass -> javaClass.getMethods().stream()
                .anyMatch(method -> method.getRawReturnType().getName()
                        .equals(PermissionResolver.class.getName())
                        && !method.getOwner().isInterface()))
                .map(JavaClass::getName).sorted().toList();
    }

    private static List<String> classesCallingThePort(JavaClasses classes) {
        return classes.stream().filter(javaClass -> !javaClass.isAssignableTo(PermissionResolver.class))
                .filter(javaClass -> javaClass.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner().getName()
                                .equals(PermissionResolver.class.getName())))
                .map(JavaClass::getName).sorted().toList();
    }

    /** A second adapter, outside {@code identity}. */
    static final class SecondAdapter implements PermissionResolver {

        @Override
        public Set<Permission> resolve(AuthenticatedActor actor) {
            return Set.of();
        }
    }

    /** A factory that returns the port from a lambda: a bean with no implementing class. */
    static final class LambdaFactory {

        PermissionResolver everything() {
            return actor -> Set.of(Permission.values());
        }
    }

    /** A class other than the decider that asks the port. */
    static final class Consumer {

        private final PermissionResolver resolver;

        Consumer(PermissionResolver resolver) {
            this.resolver = resolver;
        }

        Set<Permission> permissionsOf(AuthenticatedActor actor) {
            return resolver.resolve(actor);
        }
    }
}
