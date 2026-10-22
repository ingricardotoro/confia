package com.confia.shared.web.delay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

/**
 * Specs/web-edge, requirement "La espera materializada del retardo tiene un tope configurable",
 * scenario "Tope no válido" [WE77] (session-endpoints-cookie-and-csrf design.md, decision 8):
 * {@code confia.web.delay.max-wait} is 30 seconds by default, positive and at most 60 seconds, and a
 * value outside that range stops the start with a message that names the property. Bound with
 * Spring Boot's own {@link Binder}, as the process binds it.
 */
class DelayPropertiesTest {

    private static final String MAX_WAIT = "confia.web.delay.max-wait";

    private static DelayProperties bind(Map<String, Object> values) {
        return new Binder(ConfigurationPropertySources.from(
                List.of(new MapPropertySource("test", values))))
                .bindOrCreate("confia.web.delay", DelayProperties.class);
    }

    @Test
    void withNothingSetTheCapIsThirtySeconds() {
        assertThat(bind(Map.of()).maxWait()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theCapCanBeChangedWithoutRecompiling() {
        assertThat(bind(Map.of(MAX_WAIT, "45s")).maxWait()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void theLargestCapIsSixtySeconds() {
        assertThat(bind(Map.of(MAX_WAIT, "60s")).maxWait()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void theSmallestCapIsOneMillisecond() {
        assertThat(bind(Map.of(MAX_WAIT, "1ms")).maxWait()).isEqualTo(Duration.ofMillis(1));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0s", "-1s", "-30s", "61s", "60001ms", "2m"})
    void aCapOutsideTheRangeStopsTheStartAndNamesTheProperty(String value) {
        assertThatThrownBy(() -> bind(Map.of(MAX_WAIT, value))).satisfies(failure ->
                assertThat(messagesOf(failure)).contains(MAX_WAIT));
    }

    @Test
    void thePermitsAreStillBoundByTheirOwnProperty() {
        assertThat(bind(Map.of()).maxConcurrentWaits()).isEqualTo(200);
        assertThatThrownBy(() -> bind(Map.of("confia.web.delay.max-concurrent-waits", "0")))
                .satisfies(failure -> assertThat(messagesOf(failure))
                        .contains("confia.web.delay.max-concurrent-waits"));
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append(" | ");
        }
        return messages.toString();
    }
}
