package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.jobs.JobPayloadRules;
import com.confia.shared.jobs.JobType;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Scenarios BJ12 and BJ52 (design.md decisions 8 and 21): the payload class of every job type that a
 * class declares as a {@code static JobType} constant obeys {@link JobPayloadRules}. The fixture
 * declares a type whose payload has a {@code String email}, and the rule rejects it naming the class
 * and the component.
 *
 * <p>No production class declares a {@code JobType} yet (the first, the password-reset issuance, arrives
 * with task 3.1), so the real half evaluates the types that exist and records how many; the fixture half
 * proves the rule can fail.
 */
class JobPayloadTypesRuleTest {

    /** One message for each payload component of a declared job type that breaks the rule. */
    static List<String> violationsIn(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            for (JavaField field : javaClass.getFields()) {
                if (field.getRawType().isAssignableTo(JobType.class)
                        && field.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.STATIC)) {
                    violations.addAll(JobPayloadRules.violationsOf(payloadClassOf(field.reflect())));
                }
            }
        }
        return violations;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Record> payloadClassOf(Field field) {
        try {
            field.setAccessible(true);
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            return ((JobType<?>) field.get(null)).payloadClass();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyDeclaredProductionJobTypeHasAPayloadThatObeysTheRule() {
        assertThat(productionClasses().stream().count()).as("non-vacuous: real classes").isGreaterThan(100);

        assertThat(violationsIn(productionClasses())).isEmpty();
    }

    @Test
    void rejectsTheFixtureWhosePayloadHasAStringEmail() {
        assertThat(violationsIn(fixtureClasses())).singleElement().asString().contains("TextPayload")
                .contains("'email'").contains("java.lang.String");
    }
}
