package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ADR-0015 compliance 5, design.md decision 9 rule R4: no class may call one of jOOQ's plain-SQL
 * entry points ({@code DSL.field(String)}, {@code DSL.table(String)}, {@code
 * DSL.condition(String)}, {@code DSL.sql(String)}, {@code DSLContext.fetch(String)}, {@code
 * execute(String)}, {@code resultQuery(String)}, or a parameterized variant of any of them) unless
 * that class is in an explicit, closed approved list. That list is an immutable empty set today
 * (task 3.4): no report exists yet in this part of the change.
 *
 * <p>Matched by exact signature (owning type, method name, first raw parameter type {@code
 * String}), never by method name alone, so {@code DSL.field(Field)} is never confused with {@code
 * DSL.field(String)} (design.md decision 9, rule R4). Every listed method also has an overload
 * that appends bind values ({@code Object...}, {@code QueryPart...}); those variants still start
 * with a {@code String} first parameter, so the same first-parameter check catches them too.
 */
class NoUnapprovedPlainSqlTest {

    /** Empty and immutable today: no plain-SQL report exists yet in this part of the change. */
    private static final Set<String> APPROVED_PLAIN_SQL_CALLERS = Set.of();

    private static final String DSL_TYPE = "org.jooq.impl.DSL";
    private static final Set<String> FORBIDDEN_DSL_STATIC_METHODS =
            Set.of("field", "table", "condition", "sql");

    private static final String DSL_CONTEXT_TYPE = "org.jooq.DSLContext";
    private static final Set<String> FORBIDDEN_DSL_CONTEXT_METHODS =
            Set.of("fetch", "execute", "resultQuery");

    private static final ArchRule RULE = classes()
            .should(notCallUnapprovedPlainSql())
            .because("jOOQ plain SQL is forbidden outside the approved list (ADR-0015, regla 9)");

    @Test
    void productionCodeCallsNoUnapprovedPlainSql() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixturePlainSqlUsage() {
        assertRuleRejects(RULE, fixtureClasses(), "BadPlainSqlRepository");
    }

    private static ArchCondition<JavaClass> notCallUnapprovedPlainSql() {
        return new ArchCondition<>("not call jOOQ plain-SQL entry points outside the approved "
                + "list") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (APPROVED_PLAIN_SQL_CALLERS.contains(javaClass.getFullName())) {
                    return;
                }
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    MethodCallTarget target = call.getTarget();
                    String ownerName = target.getOwner().getFullName();
                    List<JavaClass> rawParameterTypes = target.getRawParameterTypes();
                    boolean firstParameterIsString = !rawParameterTypes.isEmpty()
                            && rawParameterTypes.get(0).getFullName()
                                    .equals(String.class.getName());
                    boolean forbidden = firstParameterIsString
                            && ((DSL_TYPE.equals(ownerName)
                                    && FORBIDDEN_DSL_STATIC_METHODS.contains(target.getName()))
                                    || (DSL_CONTEXT_TYPE.equals(ownerName)
                                            && FORBIDDEN_DSL_CONTEXT_METHODS
                                                    .contains(target.getName())));
                    if (forbidden) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                javaClass.getFullName() + " calls " + ownerName + "."
                                        + target.getName() + "(String, ...), a jOOQ plain-SQL "
                                        + "entry point not in the approved list"));
                    }
                }
            }
        };
    }
}
