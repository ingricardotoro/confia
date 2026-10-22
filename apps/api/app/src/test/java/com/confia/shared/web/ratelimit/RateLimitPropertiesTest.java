package com.confia.shared.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.RateLimitPolicy;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Specs/web-edge, requirements "Existen tres políticas de límite de tasa configuradas por
 * {@code confia.web.rate-limit.<política>.*}" and "Los límites viven en configuración y se validan
 * al arrancar" (session-endpoints-cookie-and-csrf design.md, decision 7; web-edge-foundations
 * decision 17): the defaults of the three policies, which live in {@code application.yml} and not in
 * annotations, a value an environment changes, an absent or unknown component, and a value that is
 * not positive stopping the start with a message that names the whole property. Bound with Spring
 * Boot's own {@link Binder}, as the process binds it.
 */
class RateLimitPropertiesTest {

    private static final String ROOT = "confia.web.rate-limit";
    private static final List<String> POLICIES = List.of("admin-login", "admin-mfa",
            "admin-session");

    /** The real {@code application.yml}, flattened to the keys the binder reads. */
    private static Map<String, Object> shippedDefaults() {
        try {
            Map<String, Object> flat = new LinkedHashMap<>();
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"))) {
                EnumerablePropertySource<?> keys = (EnumerablePropertySource<?>) source;
                for (String name : keys.getPropertyNames()) {
                    flat.put(name, keys.getProperty(name));
                }
            }
            return flat;
        } catch (IOException unreadable) {
            throw new IllegalStateException("application.yml cannot be read", unreadable);
        }
    }

    private static RateLimitProperties bind(Map<String, Object> values) {
        return new Binder(ConfigurationPropertySources.from(
                List.of(new MapPropertySource("test", values))))
                .bindOrCreate(ROOT, RateLimitProperties.class);
    }

    private static Map<String, Object> shippedWith(String property, Object value) {
        Map<String, Object> values = shippedDefaults();
        values.put(ROOT + "." + property, value);
        return values;
    }

    private static RateLimitPolicy policyOf(RateLimitProperties properties, String name) {
        return switch (name) {
            case "admin-login" -> properties.adminLogin().policy();
            case "admin-mfa" -> properties.adminMfa().policy();
            case "admin-session" -> properties.adminSession().policy();
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    void theDefaultsOfTheThreePoliciesAreTheOnesOfTheSpecification() {
        RateLimitProperties properties = bind(shippedDefaults());

        assertThat(properties.adminLogin().policy()).isEqualTo(new RateLimitPolicy(10,
                Duration.ofMinutes(1), 10, Duration.ofMinutes(10), Duration.ofMinutes(1),
                Duration.ofHours(1), 50_000));
        assertThat(properties.adminMfa().policy()).isEqualTo(new RateLimitPolicy(20,
                Duration.ofMinutes(1), 10, Duration.ofMinutes(10), Duration.ofMinutes(1),
                Duration.ofHours(1), 50_000));
        assertThat(properties.adminSession().policy()).isEqualTo(new RateLimitPolicy(120,
                Duration.ofMinutes(1), 30, Duration.ofMinutes(10), Duration.ofMinutes(1),
                Duration.ofHours(1), 50_000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin-login", "admin-mfa", "admin-session"})
    void layerTwoAdmitsOneAttemptAMinuteWithACapOfOneHourOnEveryPolicy(String name) {
        RateLimitPolicy policy = policyOf(bind(shippedDefaults()), name);

        assertThat(policy.restrictedInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.restrictionCap()).isEqualTo(Duration.ofHours(1));
        assertThat(policy.failureWindow()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void theDefaultsAreNotInAnnotationsBecauseTheCodeAloneBindsNothing() {
        // Without application.yml the three components are absent, so the start stops: a default
        // that lived in an annotation would give the same value to every component of the same type.
        assertThatThrownBy(() -> bind(Map.of())).satisfies(failure ->
                assertThat(messagesOf(failure)).contains(ROOT + ".admin-login"));
    }

    @Test
    void everyValueOfEveryPolicyCanBeChangedWithoutRecompiling() {
        for (String name : POLICIES) {
            Map<String, Object> values = shippedDefaults();
            String prefix = ROOT + "." + name + ".";
            values.put(prefix + "request-limit", "3");
            values.put(prefix + "request-window", "30s");
            values.put(prefix + "failure-threshold", "4");
            values.put(prefix + "failure-window", "5m");
            values.put(prefix + "restricted-interval", "2m");
            values.put(prefix + "restriction-cap", "2h");
            values.put(prefix + "max-entries", "7");

            assertThat(policyOf(bind(values), name)).as(name)
                    .isEqualTo(new RateLimitPolicy(3, Duration.ofSeconds(30), 4,
                            Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofHours(2), 7));
        }
    }

    @Test
    void changingOnePolicyLeavesTheOthersAtTheirDefaults() {
        RateLimitProperties properties = bind(shippedWith("admin-mfa.request-limit", "7"));

        assertThat(properties.adminMfa().policy().requestLimit()).isEqualTo(7);
        assertThat(properties.adminLogin().policy().requestLimit()).isEqualTo(10);
        assertThat(properties.adminSession().policy().requestLimit()).isEqualTo(120);
    }

    @ParameterizedTest
    @CsvSource({"admin-login, request-limit, 0", "admin-mfa, request-limit, -1",
            "admin-session, request-limit, 0", "admin-session, failure-threshold, -5",
            "admin-mfa, max-entries, 0", "admin-login, request-window, 0s",
            "admin-session, request-window, -1s", "admin-mfa, failure-window, 0s",
            "admin-session, failure-window, -10m", "admin-login, restricted-interval, 0s",
            "admin-mfa, restricted-interval, -1m", "admin-session, restriction-cap, 0s",
            "admin-login, restriction-cap, -1h"})
    void aValueThatIsNotPositiveStopsTheStartAndNamesTheWholeProperty(String policy,
            String property, String value) {
        Map<String, Object> values = shippedWith(policy + "." + property, value);

        assertThatThrownBy(() -> bind(values)).satisfies(failure ->
                assertThat(messagesOf(failure)).contains(ROOT + "." + policy + "." + property));
    }

    @ParameterizedTest
    @CsvSource({"admin-login, request-limit, 10001, 10000",
            "admin-mfa, failure-threshold, 10001, 10000",
            "admin-session, max-entries, 1000001, 1000000"})
    void aCountAboveTheBoundOfThePolicyStopsTheStartAndNamesTheWholeProperty(String policy,
            String property, String value, String bound) {
        Map<String, Object> values = shippedWith(policy + "." + property, value);

        assertThatThrownBy(() -> bind(values)).satisfies(failure ->
                assertThat(messagesOf(failure)).contains(ROOT + "." + policy + "." + property)
                        .contains("must not exceed " + bound));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin-login", "admin-mfa", "admin-session"})
    void anAbsentComponentStopsTheStartAndNamesTheProperty(String missing) {
        Map<String, Object> values = shippedDefaults();
        values.keySet().removeIf(key -> key.startsWith(ROOT + "." + missing + "."));

        assertThatThrownBy(() -> bind(values)).satisfies(failure ->
                assertThat(messagesOf(failure)).contains(ROOT + "." + missing));
    }

    @Test
    void anUnknownComponentStopsTheStartAndNamesTheProperty() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(ROOT + ".admin-otro.request-limit=3")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .satisfies(failure -> assertThat(messagesOf(failure))
                                .contains(ROOT + ".admin-otro")));
    }

    @Test
    void aMisspelledPropertyStopsTheStartInsteadOfLeavingTheDefault() {
        // A typo in a security control must not quietly keep ten requests a minute.
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(ROOT + ".admin-login.request-limt=3")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .satisfies(failure -> assertThat(messagesOf(failure))
                                .contains(ROOT + ".admin-login.request-limt")));
    }

    @Test
    void aWellSpelledPropertyStillStarts() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(ROOT + ".admin-session.request-limit=3")
                .run(context -> assertThat(context.getBean(RateLimitProperties.class)
                        .adminSession().policy().requestLimit()).isEqualTo(3));
    }

    @Test
    void theShippedConfigurationStartsWithoutAnyOverride() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesOnly.class)
                .run(context -> assertThat(context.getBean(RateLimitProperties.class)
                        .adminLogin().policy().requestLimit()).isEqualTo(10));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class PropertiesOnly {
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append(" | ");
        }
        return messages.toString();
    }
}
