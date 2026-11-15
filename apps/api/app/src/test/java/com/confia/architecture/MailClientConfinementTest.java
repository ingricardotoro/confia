package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Scenario BI150 (transactional-email-adapter design.md decision 3; docs/08 section 8.4): the mail
 * client, that is {@code jakarta.mail}, {@code org.eclipse.angus} and {@code org.springframework.mail},
 * appears only in {@code com.confia.notifications.infrastructure} and its sub-packages. Every other
 * module sends mail through the public use case of {@code notifications}, never through the client.
 * Same pattern as {@link DbSchedulerConfinementTest}; no other package is exempt by name.
 */
class MailClientConfinementTest {

    private static final ArchRule RULE = noClasses()
            .that().resideOutsideOfPackage("com.confia.notifications.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.mail..", "org.eclipse.angus..", "org.springframework.mail..")
            .because("the mail client stays inside notifications.infrastructure "
                    + "(docs/08 section 8.4; design.md decision 3)");

    @Test
    void onlyTheNotificationsInfrastructureDependsOnTheMailClient() {
        RULE.check(productionClasses());
    }

    /**
     * Non-vacuity: the rule matches by package name, so it would also pass if the library were not
     * on the class path at all and the fixtures below were silently not compiled.
     */
    @Test
    void theMailClientIsOnTheClassPathSoTheRuleIsNotVacuous() {
        assertThat(fixtureClasses().stream()
                .filter(c -> c.getPackageName().startsWith("com.confia.architecture.fixture.mail"))
                .count())
                .as("the two mail fixtures are present").isEqualTo(2);
        assertThat(loadable("jakarta.mail.internet.InternetAddress")).isTrue();
        assertThat(loadable("org.springframework.mail.SimpleMailMessage")).isTrue();
    }

    @Test
    void rejectsTheInfrastructureFixtureOfAnotherModule() {
        assertRuleRejects(RULE, fixtureClasses(), "ImportsJakartaMail", "jakarta.mail");
    }

    @Test
    void rejectsTheApplicationFixtureOfTheNotificationsModule() {
        assertRuleRejects(RULE, fixtureClasses(), "ImportsSpringMail", "org.springframework.mail");
    }

    private static boolean loadable(String className) {
        try {
            Class.forName(className, false, MailClientConfinementTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
