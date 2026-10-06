package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.metaAnnotatedWith;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;

/**
 * Rules W2a and W2b (web-edge-foundations design.md, decision 20; specs/build-integrity,
 * requirement "Ningún tipo expuesto por la capa {@code web} es una entidad de dominio ni un
 * registro de jOOQ"). Everything a web package says to the client goes through an explicit DTO
 * (CLAUDE.md, rule 10): neither the public signature of a controller nor a field or record
 * component of a web class may be a {@code domain} type, a jOOQ {@code Record} or a type of the
 * generated jOOQ package.
 *
 * <p><b>W2a</b> evaluates the public methods of classes meta-annotated with {@code @Controller}
 * (so {@code @RestController} too), over the return type, every parameter type and the type
 * arguments of both. No production class is a controller yet, so its production half declares the
 * empty-set exception of ADR-0018, listed in {@link EmptyShouldExceptionInventoryTest} with its
 * owner, {@code session-tokens-and-web-layer}. Its fixture half never does.
 *
 * <p><b>W2b</b> evaluates every field of every class in a {@code ..web..} package, which includes
 * the private final fields that a record's components are. It carries no exception: {@code
 * shared.web} already holds real records ({@code ProblemBody}, {@code FieldViolation} and {@code
 * PublicEndpoint}), and {@link #theRecordRuleEvaluatesTheRealRecords()} proves it by asking the
 * rule's own scope, so the production half cannot pass because it looked at nothing.
 */
class WebExposedTypesRuleTest {

    private static final String RECORD_TYPE = "org.jooq.Record";

    /** A domain type, the generated jOOQ package or a jOOQ {@code Record} of any kind. */
    private static final DescribedPredicate<JavaClass> DOMAIN_OR_PERSISTENCE_TYPE =
            resideInAnyPackage("..domain..", "confia.generated.jooq..")
                    .or(DescribedPredicate.describe("assignable to " + RECORD_TYPE,
                            type -> type.isAssignableTo(RECORD_TYPE)));

    /** What no field of a web class may be: the above, or any other jOOQ type. */
    private static final DescribedPredicate<JavaClass> FORBIDDEN_FIELD_TYPE =
            DOMAIN_OR_PERSISTENCE_TYPE.or(resideInAPackage("org.jooq.."));

    /** The scope of W2b, shared with {@link #theRecordRuleEvaluatesTheRealRecords()}. */
    private static final DescribedPredicate<JavaField> FIELD_OF_A_WEB_CLASS =
            DescribedPredicate.describe("declared in a class of a ..web.. package",
                    field -> resideInAPackage("..web..").test(field.getOwner()));

    private static ArchRule controllerSignatureRule() {
        return methods()
                .that().arePublic()
                .and().areDeclaredInClassesThat(metaAnnotatedWith(Controller.class))
                .should(notExposeDomainOrPersistenceTypes())
                .because("a controller answers with an explicit DTO, never a domain object or a "
                        + "jOOQ record (CLAUDE.md, rule 10)");
    }

    private static ArchRule dataCarrierFieldRule() {
        return fields()
                .that(FIELD_OF_A_WEB_CLASS)
                .should(notHaveDomainOrPersistenceType())
                .because("a DTO of a web package carries no domain object and no jOOQ record "
                        + "(CLAUDE.md, rule 10)");
    }

    @Test
    void productionControllersExposeNoDomainOrPersistenceType() {
        // ADR-0018: no production class is a controller yet. Owner: session-tokens-and-web-layer
        // (first production endpoint). Declared in EmptyShouldExceptionInventoryTest.
        controllerSignatureRule().allowEmptyShould(true).check(productionClasses());
    }

    @Test
    void rejectsTheFixtureControllerThatExposesARecordAndADomainType() {
        assertRuleRejects(controllerSignatureRule(), fixtureClasses(),
                "BadRecordReturningController", "org.jooq.Record", "BadDomain");
    }

    @Test
    void productionWebClassesCarryNoDomainOrPersistenceField() {
        dataCarrierFieldRule().check(productionClasses());
    }

    @Test
    void rejectsTheFixtureDtoThatCarriesADomainTypeAndARecord() {
        assertRuleRejects(dataCarrierFieldRule(), fixtureClasses(), "BadDomainCarryingDto",
                "BadDomain", "org.jooq.Record");
    }

    /**
     * Non-vacuity of W2b: the fields the rule evaluates in production belong to the three real
     * records the specification names, and those are records (so their components are among them).
     */
    @Test
    void theRecordRuleEvaluatesTheRealRecords() {
        Set<String> owners = new TreeSet<>();
        for (JavaClass javaClass : productionClasses()) {
            for (JavaField field : javaClass.getFields()) {
                if (FIELD_OF_A_WEB_CLASS.test(field)) {
                    owners.add(field.getOwner().getFullName());
                }
            }
        }

        assertThat(owners).contains("com.confia.shared.web.problem.ProblemBody",
                "com.confia.shared.web.problem.FieldViolation",
                "com.confia.shared.web.edge.PublicEndpoint");
        for (String name : List.of("com.confia.shared.web.problem.ProblemBody",
                "com.confia.shared.web.problem.FieldViolation",
                "com.confia.shared.web.edge.PublicEndpoint")) {
            assertThat(productionClasses().get(name).isRecord())
                    .as("%s must be a record, or the rule would not be looking at record "
                            + "components", name)
                    .isTrue();
        }
    }

    private static ArchCondition<JavaMethod> notExposeDomainOrPersistenceTypes() {
        return new ArchCondition<>("not expose a domain, jOOQ record or generated jOOQ type in "
                + "a public signature") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                List<JavaType> signature = new ArrayList<>(method.getParameterTypes());
                signature.add(method.getReturnType());
                for (JavaType type : signature) {
                    for (JavaClass involved : type.getAllInvolvedRawTypes()) {
                        if (DOMAIN_OR_PERSISTENCE_TYPE.test(involved)) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                    + " exposes " + involved.getFullName() + " in its public "
                                    + "signature, which a controller must replace with an "
                                    + "explicit DTO"));
                        }
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaField> notHaveDomainOrPersistenceType() {
        return new ArchCondition<>("not have a domain, jOOQ record or generated jOOQ type") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                for (JavaClass involved : field.getType().getAllInvolvedRawTypes()) {
                    if (FORBIDDEN_FIELD_TYPE.test(involved)) {
                        events.add(SimpleConditionEvent.violated(field, field.getFullName()
                                + " is or contains " + involved.getFullName() + ", which a web "
                                + "class must replace with explicit values"));
                    }
                }
            }
        };
    }
}
