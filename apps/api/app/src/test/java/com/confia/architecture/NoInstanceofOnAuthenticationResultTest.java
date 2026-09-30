package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.InstanceofCheck;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Reinforcement rule for column-encryption-and-mfa-totp design.md decision 6 (§9, sonda S4 — not
 * a blocking sonda, built because it turned out viable): no production class of {@code
 * com.confia.identity..} may decide anything through an {@code instanceof} check against {@link
 * com.confia.identity.domain.AuthenticationResult} or any of its permitted outcomes.
 *
 * <p>This does <b>not</b> replace decision 6's own compilation fixture ({@code
 * ExhaustiveAuthenticationResultSwitchCompilationTest}), which is the mechanism specs/identity/
 * spec.md's requirement "Exhaustividad forzada por el compilador..." actually names: a broken
 * exhaustive {@code switch} fails to compile, full stop, everywhere. This rule catches a different
 * regression that fixture structurally cannot: someone reverting an existing exhaustive {@code
 * switch} back to {@code instanceof}, which the compiler would accept silently — the exact defect
 * this whole change exists to close (proposal.md, "La costura con la parte 1"). {@code
 * AuthenticateWithPassword} itself no longer contains any such check after column-encryption-and-
 * mfa-totp's own task 4.1; this rule is what keeps it that way.
 *
 * <p>Sonda S4 confirmed, by inspecting the fixed ArchUnit core version this repository resolves
 * (1.4.2, via {@code spring-modulith-core:2.1.1} — the version asymmetry against the {@code
 * archunit-junit5*} wrappers is pre-existing and already justified in {@code apps/api/pom.xml} as
 * W6, untouched here): {@link JavaClass#getInstanceofChecks()} returns a
 * {@code Set<InstanceofCheck>}, and {@link InstanceofCheck} exposes {@code getRawType()}, {@code
 * getOwner()}, {@code getLineNumber()} and {@code getSourceCodeLocation()} — no precedent of a
 * rule in this tree inspecting that pattern before this one, but the API is there.
 */
class NoInstanceofOnAuthenticationResultTest {

    private static final String IDENTITY_PACKAGE_PREFIX = "com.confia.identity";
    private static final String SIMULATED_IDENTITY_FIXTURE_PACKAGE =
            "com.confia.architecture.fixture.identity";

    /** {@code AuthenticationResult}'s own type and its four permitted outcomes, by binary name
     * ({@code $}-nested, exactly how {@link JavaClass#getFullName()} reports a nested record). */
    private static final Set<String> FORBIDDEN_INSTANCEOF_TARGETS = Set.of(
            "com.confia.identity.domain.AuthenticationResult",
            "com.confia.identity.domain.AuthenticationResult$Authenticated",
            "com.confia.identity.domain.AuthenticationResult$Rejected",
            "com.confia.identity.domain.AuthenticationResult$SecondFactorRequired",
            "com.confia.identity.domain.AuthenticationResult$SecondFactorEnrollmentRequired");

    private static final ArchRule RULE = classes()
            .should(notCheckInstanceofAgainstAuthenticationResult())
            .because("no production class of com.confia.identity.. may decide anything through "
                    + "instanceof against AuthenticationResult or its permitted outcomes: the "
                    + "exhaustive switch column-encryption-and-mfa-totp design.md decision 6 "
                    + "forces is the only permitted mechanism (specs/identity/spec.md, "
                    + "\"Exhaustividad forzada por el compilador...\")");

    @Test
    void productionCodeInIdentityNeverChecksInstanceofAgainstAuthenticationResult() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureInstanceofUsage() {
        assertRuleRejects(RULE, fixtureClasses(), "BadInstanceofOnAuthenticationResult",
                "AuthenticationResult");
    }

    private static ArchCondition<JavaClass> notCheckInstanceofAgainstAuthenticationResult() {
        return new ArchCondition<>("not check instanceof against AuthenticationResult or its "
                + "permitted outcomes from com.confia.identity..") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!isIdentityScoped(javaClass)) {
                    return;
                }
                for (InstanceofCheck instanceofCheck : javaClass.getInstanceofChecks()) {
                    String rawTypeName = instanceofCheck.getRawType().getFullName();
                    if (FORBIDDEN_INSTANCEOF_TARGETS.contains(rawTypeName)) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                javaClass.getFullName() + " checks instanceof against "
                                        + rawTypeName + " at line "
                                        + instanceofCheck.getLineNumber() + ", forbidden in "
                                        + "com.confia.identity.. — the exhaustive switch is the "
                                        + "only permitted mechanism"));
                    }
                }
            }
        };
    }

    private static boolean isIdentityScoped(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        return packageName.equals(IDENTITY_PACKAGE_PREFIX)
                || packageName.startsWith(IDENTITY_PACKAGE_PREFIX + ".")
                || packageName.equals(SIMULATED_IDENTITY_FIXTURE_PACKAGE);
    }
}
