package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Scenario [WE64] of specs/web-edge, second half (session-endpoints-cookie-and-csrf design.md
 * decision 5; the owner's decision D-N1): the cookie helper has no configuration surface at all, so
 * no property can turn {@code Secure} off or rename the cookies. Two checks that fail for the same
 * reason: no class of the helper or of its configuration depends on a type that reads a property
 * ({@code Environment}, {@code @Value}, {@code @ConfigurationProperties}), and no key of the
 * configuration files of the process mentions a cookie or {@code Secure}. The first has a permanent
 * fixture that must be rejected; the second is shown to find a key on a set of properties that adds
 * one.
 */
class SessionCookieConfigurationSurfaceTest {

    private static final ArchRule RULE = noClasses()
            .that().resideInAnyPackage("com.confia.shared.web.session..",
                    "com.confia.architecture.fixture.cookie.surface..")
            .or().haveFullyQualifiedName("com.confia.shared.web.edge.SessionCookieConfiguration")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.core.env..", "org.springframework.boot.context.properties..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.beans.factory.annotation.Value")
            .because("the cookie helper reads no property: its attributes are fixed in code");

    @Test
    void theCookieHelperAndItsConfigurationReadNoProperty() {
        RULE.check(productionClasses());
    }

    @Test
    void theRuleLooksAtRealClassesOfTheHelperAndItsConfiguration() {
        assertThat(productionClasses().stream().map(JavaClass::getName)
                .filter(name -> name.startsWith("com.confia.shared.web.session.")
                        || name.equals("com.confia.shared.web.edge.SessionCookieConfiguration")))
                .contains("com.confia.shared.web.session.SessionCookies",
                        "com.confia.shared.web.edge.SessionCookieConfiguration");
    }

    @Test
    void theRuleRejectsAConfigurationThatReadsAPropertyToDecideSecure() {
        assertRuleRejects(RULE, fixtureClasses(), "BadSessionCookieConfiguration", "Environment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-local.yml", "application-preprod.yml"})
    void noKeyOfTheConfigurationFilesMentionsACookieOrSecure(String file) {
        Properties properties = load(file);

        assertThat(properties.keySet()).as("the file %s has keys", file).isNotEmpty();
        assertThat(keysThatMentionTheCookie(properties)).isEmpty();
    }

    @Test
    void aKeyThatMentionsTheCookieOrSecureIsFound() {
        Properties properties = new Properties();
        properties.setProperty("confia.web.session-cookie.secure", "false");
        properties.setProperty("server.servlet.session.cookie.name", "SID");
        properties.setProperty("confia.web.rate-limit.admin-login.request-limit", "10");

        assertThat(keysThatMentionTheCookie(properties)).containsExactlyInAnyOrder(
                "confia.web.session-cookie.secure", "server.servlet.session.cookie.name");
    }

    private static List<String> keysThatMentionTheCookie(Properties properties) {
        List<String> found = new ArrayList<>();
        for (Object name : properties.keySet()) {
            String key = String.valueOf(name);
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("cookie") || lower.contains("secure")) {
                found.add(key);
            }
        }
        return found;
    }

    private static Properties load(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        Properties properties = yaml.getObject();
        if (properties == null) {
            throw new IllegalStateException(file + " could not be read");
        }
        return properties;
    }
}
