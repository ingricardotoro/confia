package com.confia.shared.web.delay;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.edge.RequiredDelayConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Specs/web-edge, requirement "Un semáforo acotado limita las esperas...", scenario "Número de
 * permisos no válido", and design.md decision 18, "Configuración": the permits come from {@code
 * confia.web.delay.max-concurrent-waits} (200 by default), the start fails with a message that names
 * the property when it is not positive, when it is more than half of the connections the server
 * accepts, or when the process does not run its requests on virtual threads. Started through the
 * real configuration with the real binder, as the administrative process starts it.
 */
class RequiredDelayConfigurationTest {

    private static final String WAITS = "confia.web.delay.max-concurrent-waits";
    private static final String MAX_WAIT = "confia.web.delay.max-wait";
    private static final String CONNECTIONS = "server.tomcat.max-connections";
    private static final String VIRTUAL = "spring.threads.virtual.enabled";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RequiredDelayConfiguration.class)
            .withPropertyValues(VIRTUAL + "=true");

    @Test
    void withNothingSetTheMaterializerAdmitsTwoHundredWaits() {
        runner.run(context -> assertThat(context.getBean(RequiredDelayMaterializer.class)
                .availablePermits()).isEqualTo(200));
    }

    @Test
    void theNumberOfWaitsCanBeChangedWithoutRecompiling() {
        runner.withPropertyValues(WAITS + "=25").run(context -> assertThat(
                context.getBean(RequiredDelayMaterializer.class).availablePermits())
                .isEqualTo(25));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0", "-1"})
    void aNumberOfWaitsThatIsNotPositiveStopsTheStartAndNamesTheProperty(String value) {
        runner.withPropertyValues(WAITS + "=" + value)
                .run(context -> assertThat(messagesOfTheFailure(context)).contains(WAITS));
    }

    // --- The cap of the wait (session-endpoints-cookie-and-csrf design.md, decision 8) ---

    @Test
    void withNothingSetTheWaitIsCappedAtThirtySeconds() {
        runner.run(context -> assertThat(context.getBean(RequiredDelayMaterializer.class)
                .maxWait()).isEqualTo(Duration.ofSeconds(30)));
    }

    @Test
    void theCapCanBeChangedWithoutRecompiling() {
        runner.withPropertyValues(MAX_WAIT + "=5s").run(context -> assertThat(
                context.getBean(RequiredDelayMaterializer.class).maxWait())
                .isEqualTo(Duration.ofSeconds(5)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0s", "-1s", "61s", "1h"})
    void aCapThatIsNotValidStopsTheStartAndNamesTheProperty(String value) {
        runner.withPropertyValues(MAX_WAIT + "=" + value)
                .run(context -> assertThat(messagesOfTheFailure(context)).contains(MAX_WAIT));
    }

    @Test
    void aCapOfSixtySecondsIsTheLargestThatStarts() {
        runner.withPropertyValues(MAX_WAIT + "=60s").run(context -> assertThat(context)
                .hasNotFailed());
    }

    @Test
    void aMisspelledPropertyStopsTheStartInsteadOfLeavingTheDefault() {
        runner.withPropertyValues("confia.web.delay.max-concurent-waits=5")
                .run(context -> assertThat(messagesOfTheFailure(context))
                        .contains("confia.web.delay.max-concurent-waits"));
    }

    @Test
    void halfOfTheConnectionsTheServerAcceptsIsTheMostThatMayWait() {
        runner.withPropertyValues(CONNECTIONS + "=100", WAITS + "=50")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void moreThanHalfOfTheConnectionsTheServerAcceptsStopsTheStartNamingBothProperties() {
        runner.withPropertyValues(CONNECTIONS + "=100", WAITS + "=51")
                .run(context -> assertThat(messagesOfTheFailure(context)).contains(WAITS)
                        .contains(CONNECTIONS));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0", "-1"})
    void aConnectionLimitThatIsNotPositiveStopsTheStartWithItsOwnReason(String value) {
        // -1 is "no limit" to Tomcat: half of no limit bounds nothing, so the start must stop and
        // say why, not report the waits as more than half of a negative number.
        runner.withPropertyValues(CONNECTIONS + "=" + value)
                .run(context -> assertThat(messagesOfTheFailure(context))
                        .contains(CONNECTIONS + " must be positive")
                        .doesNotContain("more than half"));
    }

    @Test
    void theDefaultNumberOfWaitsFitsTheDefaultConnectionLimitOfTomcat() {
        runner.withPropertyValues(WAITS + "=4096")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(WAITS + "=4097")
                .run(context -> assertThat(messagesOfTheFailure(context)).contains(CONNECTIONS));
    }

    @Test
    void aProcessWithoutVirtualThreadsDoesNotStart() {
        new ApplicationContextRunner().withUserConfiguration(RequiredDelayConfiguration.class)
                .run(context -> assertThat(messagesOfTheFailure(context)).contains(VIRTUAL));
        new ApplicationContextRunner().withUserConfiguration(RequiredDelayConfiguration.class)
                .withPropertyValues(VIRTUAL + "=false")
                .run(context -> assertThat(messagesOfTheFailure(context)).contains(VIRTUAL));
    }

    private static String messagesOfTheFailure(AssertableApplicationContext context) {
        assertThat(context).as("the start must fail").hasFailed();
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = context.getStartupFailure(); cause != null;
                cause = cause.getCause()) {
            messages.append(cause.getMessage()).append(" | ");
        }
        return messages.toString();
    }
}
