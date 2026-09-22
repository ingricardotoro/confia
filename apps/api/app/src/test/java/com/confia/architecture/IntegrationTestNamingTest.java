package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.BASE_PACKAGE;
import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.PostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.annotation.Annotation;
import java.util.Set;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * Nomenclatura obligatoria {@code *IT} for every subclass of {@code PostgresIntegrationTest}
 * (deuda W3, design.md decision 13; specs/build-integrity/spec.md). Evaluated over {@code
 * ONLY_INCLUDE_TESTS} classes (design.md §6.4's own sketch): a rule about test classes, not
 * production ones, so it cannot reuse {@link ArchitectureTestSupport#productionClasses()}.
 *
 * <p><b>Two conditions trigger the required suffix</b> (design.md decision 13): (a) any
 * <em>concrete</em> subclass of {@link PostgresIntegrationTest} — abstract intermediate bases
 * ({@code PostgresIntegrationTest} itself, {@code TransactionalPostgresIntegrationTest}, {@code
 * CommittingPostgresIntegrationTest}) are exempt, since none of them is ever a leaf test Surefire
 * or Failsafe could select and run; or (b) any class that depends directly on {@link
 * SharedPostgresContainer} <em>and</em> declares at least one recognized test-trigger method
 * (JUnit Jupiter or jqwik {@code @Property}) — condition (b) is deliberately narrower than "any
 * class that depends on the container", because {@code AuditLogSuperuserTamper} (PR B3b) is a
 * real, permanent test-support helper that depends on {@link SharedPostgresContainer} directly
 * but declares no test method and matches neither Surefire's nor Failsafe's default include
 * pattern on its own; design.md's own rationale for condition (b) names "la prueba de
 * propiedades" (a class jqwik or JUnit will actually try to run), not an arbitrary dependent
 * helper — reported here as a discrepancy resolved by implementation, per design.md's own
 * "esbozo" warning that the exact signature is the implementation's to fix (§6.4).
 */
class IntegrationTestNamingTest {

    private static final String IT_SUFFIX = "IT";

    private static final String FIXTURE_NAMING_LOCATION_FRAGMENT = "/architecture/fixture/naming/";

    private static final Set<Class<? extends Annotation>> TEST_TRIGGER_ANNOTATIONS = Set.of(
            Test.class, ParameterizedTest.class, RepeatedTest.class, TestFactory.class,
            TestTemplate.class, net.jqwik.api.Property.class);

    private static final ArchRule RULE = classes()
            .should(beNamedWithTheItSuffixWhenItNeedsARealPostgresContainer())
            .because("Surefire selects a *Test-named class by file pattern and the JUnit platform "
                    + "loads it looking for test methods; extending PostgresIntegrationTest or "
                    + "depending directly on SharedPostgresContainer from a real test needs a "
                    + "container, and the *IT suffix is how this codebase marks that (design.md "
                    + "decision 13, decision 14; specs/build-integrity, deuda W3)");

    @Test
    void realTestClassesThatNeedAContainerAreAllNamedWithTheItSuffix() {
        JavaClasses realClasses = realTestClasses();
        long classesThisRuleActuallyEvaluates = realClasses.stream()
                .filter(IntegrationTestNamingTest::needsARealPostgresContainer)
                .count();
        assertThat(classesThisRuleActuallyEvaluates)
                .as("the real tree must contain at least one class this rule actually evaluates — "
                        + "otherwise the check below passes over an empty set and proves nothing")
                .isGreaterThan(0);

        RULE.check(realClasses);
    }

    @Test
    void rejectsTheFixtureNamedWithTheTestSuffixInsteadOfIt() {
        assertRuleRejects(RULE, fixtureClasses(), "BadlyNamedContainerTest", IT_SUFFIX);
    }

    /**
     * Every real test class in the tree, {@code ONLY_INCLUDE_TESTS}, minus this rule's own
     * permanent negative fixture package — otherwise the production half above would fail against
     * the very fixture task 6.2 introduces on purpose.
     */
    private static JavaClasses realTestClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains(FIXTURE_NAMING_LOCATION_FRAGMENT))
                .importPackages(BASE_PACKAGE);
    }

    private static ArchCondition<JavaClass> beNamedWithTheItSuffixWhenItNeedsARealPostgresContainer() {
        return new ArchCondition<>("be named with the IT suffix when it needs a real PostgreSQL "
                + "container (extends PostgresIntegrationTest, or depends directly on "
                + "SharedPostgresContainer from a real test-method-bearing class)") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!needsARealPostgresContainer(javaClass)) {
                    return;
                }
                if (!javaClass.getSimpleName().endsWith(IT_SUFFIX)) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getFullName() + " needs a real PostgreSQL container but is "
                                    + "not named with the required '" + IT_SUFFIX + "' suffix"));
                }
            }
        };
    }

    private static boolean needsARealPostgresContainer(JavaClass javaClass) {
        return isConcretePostgresIntegrationTestSubclass(javaClass)
                || isATestEntryPointDependingDirectlyOnTheSharedContainer(javaClass);
    }

    /**
     * Condition (a): assignable to {@link PostgresIntegrationTest} and not itself abstract.
     * Excluding abstract classes is what naturally exempts {@code PostgresIntegrationTest} itself
     * and its two abstract variants without hardcoding their names — none of the three is ever a
     * leaf test class the JUnit platform could instantiate and run.
     */
    private static boolean isConcretePostgresIntegrationTestSubclass(JavaClass javaClass) {
        return !javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
                && javaClass.isAssignableTo(PostgresIntegrationTest.class);
    }

    /**
     * Condition (b): an actual method call on {@link SharedPostgresContainer} (never merely a
     * class-literal reference — an ArchUnit rule that names {@code SharedPostgresContainer.class}
     * for its own bytecode scan, exactly like this rule does, is not "using" the container) from a
     * class that itself declares at least one recognized test-trigger method — the jqwik
     * {@code @Property} case design.md decision 13 names explicitly, and any equivalent JUnit
     * case. A class that merely uses {@link SharedPostgresContainer} as a plain helper, with no
     * test method of its own (for example {@code AuditLogSuperuserTamper}), is not itself a leaf
     * Surefire or Failsafe could select, so it is deliberately out of this condition's scope.
     */
    private static boolean isATestEntryPointDependingDirectlyOnTheSharedContainer(JavaClass javaClass) {
        boolean callsTheSharedContainer = javaClass.getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getTargetOwner().isEquivalentTo(SharedPostgresContainer.class));
        return callsTheSharedContainer && hasARecognizedTestTriggerMethod(javaClass);
    }

    private static boolean hasARecognizedTestTriggerMethod(JavaClass javaClass) {
        for (JavaMethod method : javaClass.getMethods()) {
            for (Class<? extends Annotation> annotation : TEST_TRIGGER_ANNOTATIONS) {
                if (method.isAnnotatedWith(annotation)) {
                    return true;
                }
            }
        }
        return false;
    }
}
