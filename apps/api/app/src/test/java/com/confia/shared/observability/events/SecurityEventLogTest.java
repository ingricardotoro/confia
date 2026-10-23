package com.confia.shared.observability.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link SecurityEventLog} (session-endpoints-cookie-and-csrf design.md decision 12): one {@code
 * WARN} line that carries the name of the event and the identifier of the request and nothing else,
 * so what the event is about stays in the audit trail and never reaches a log.
 */
class SecurityEventLogTest {

    private static final String EVENT = "identity.login.failure_spread_detected";

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SecurityEventLog.class);

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    @Test
    void writesOneWarnWithTheEventNameAndTheRequestIdentifierOnly() {
        UUID requestId = UUID.randomUUID();

        new SecurityEventLog().warn(EVENT, requestId);

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(EVENT);
            assertThat(event.getArgumentArray()).isNull();
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs()).singleElement().satisfies(pair -> {
                assertThat(pair.key).isEqualTo("requestId");
                assertThat(pair.value).isEqualTo(requestId.toString());
            });
        });
    }

    @Test
    void rejectsAnEventNameThatIsNotAClosedIdentifier() {
        SecurityEventLog log = new SecurityEventLog();
        UUID requestId = UUID.randomUUID();

        for (String bad : new String[] {"", " ", "Identity.login", "identity login", "identity.login\nforged",
                "1identity", "a".repeat(81)}) {
            assertThatThrownBy(() -> log.warn(bad, requestId))
                    .as("event name %s", bad.length() > 20 ? "of length " + bad.length() : bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(logged.list).as("a rejected name writes nothing").isEmpty();
    }

    @Test
    void acceptsAnEventNameOfEightyCharacters() {
        new SecurityEventLog().warn("a" + "b".repeat(79), UUID.randomUUID());

        assertThat(logged.list).hasSize(1);
    }

    @Test
    void rejectsNullArguments() {
        SecurityEventLog log = new SecurityEventLog();

        assertThatThrownBy(() -> log.warn(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("event");
        assertThatThrownBy(() -> log.warn(EVENT, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("requestId");
    }
}
