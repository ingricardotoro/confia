package com.confia.shared.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.RateLimitPolicy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

/**
 * Specs/web-edge, requirement "Los límites viven en configuración y se validan al arrancar"
 * (design.md, decision 17): the defaults of the administrative login, a value that an environment
 * changes, and a value that is not positive stopping the start with a message that names the
 * property. Bound with Spring Boot's own {@link Binder}, as the process binds it.
 */
class RateLimitPropertiesTest {

    private static final String PREFIX = "confia.web.rate-limit.admin-login";

    private static RateLimitProperties bind(Map<String, Object> values) {
        return new Binder(ConfigurationPropertySources.from(
                List.of(new MapPropertySource("test", values))))
                .bindOrCreate(PREFIX, RateLimitProperties.class);
    }

    private static Map<String, Object> with(String property, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PREFIX + "." + property, value);
        return values;
    }

    @Test
    void withNothingSetThePolicyIsTheOneOfLayersOneAndTwo() {
        RateLimitPolicy policy = bind(Map.of()).policy();

        assertThat(policy.requestLimit()).isEqualTo(10);
        assertThat(policy.requestWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.failureThreshold()).isEqualTo(10);
        assertThat(policy.failureWindow()).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.restrictedInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.restrictionCap()).isEqualTo(Duration.ofHours(1));
        assertThat(policy.maxEntries()).isEqualTo(50_000);
    }

    @Test
    void everyValueCanBeChangedWithoutRecompiling() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PREFIX + ".request-limit", "3");
        values.put(PREFIX + ".request-window", "30s");
        values.put(PREFIX + ".failure-threshold", "4");
        values.put(PREFIX + ".failure-window", "5m");
        values.put(PREFIX + ".restricted-interval", "2m");
        values.put(PREFIX + ".restriction-cap", "2h");
        values.put(PREFIX + ".max-entries", "7");

        RateLimitPolicy policy = bind(values).policy();

        assertThat(policy).isEqualTo(new RateLimitPolicy(3, Duration.ofSeconds(30), 4,
                Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofHours(2), 7));
    }

    @ParameterizedTest
    @CsvSource({"request-limit, 0", "request-limit, -1", "failure-threshold, 0",
            "failure-threshold, -5", "max-entries, 0", "max-entries, -1", "request-window, 0s",
            "request-window, -1s", "failure-window, 0s", "failure-window, -10m",
            "restricted-interval, 0s", "restricted-interval, -1m", "restriction-cap, 0s",
            "restriction-cap, -1h"})
    void aValueThatIsNotPositiveStopsTheStartAndNamesTheProperty(String property, String value) {
        Map<String, Object> values = with(property, value);

        assertThatThrownBy(() -> bind(values)).satisfies(failure ->
                assertThat(messagesOf(failure)).contains(PREFIX + "." + property));
    }

    @ParameterizedTest
    @CsvSource({"request-limit, 10001, 10000", "failure-threshold, 10001, 10000",
            "max-entries, 1000001, 1000000"})
    void aCountAboveTheBoundOfThePolicyStopsTheStartAndNamesTheProperty(String property,
            String value, String bound) {
        Map<String, Object> values = with(property, value);

        assertThatThrownBy(() -> bind(values)).satisfies(failure ->
                assertThat(messagesOf(failure)).contains(PREFIX + "." + property)
                        .contains("must not exceed " + bound));
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append(" | ");
        }
        return messages.toString();
    }
}
