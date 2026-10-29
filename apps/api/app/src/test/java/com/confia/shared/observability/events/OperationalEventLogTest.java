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
 * {@link OperationalEventLog}: one {@code WARN} line that carries the name of the operational event, the
 * identifier of the job run, the task and the institution and nothing else, so what the event is about stays
 * in the audit trail and never reaches a log.
 */
class OperationalEventLogTest {

    private static final String EVENT = "retention_purge_backlog";
    private static final String TASK = "identity.purge-password-reset-tokens";
    private static final UUID INSTITUTION = UUID.randomUUID();

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(OperationalEventLog.class);

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
    void writesOneWarnWithTheEventNameAndTheRunTaskAndInstitutionOnly() {
        UUID runId = UUID.randomUUID();

        new OperationalEventLog().warn(EVENT, runId, TASK, INSTITUTION);

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(EVENT);
            assertThat(event.getArgumentArray()).isNull();
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                    .containsExactly("runId=" + runId, "task=" + TASK, "institution=" + INSTITUTION);
        });
    }

    @Test
    void rejectsAnEventNameThatIsNotAClosedIdentifier() {
        OperationalEventLog log = new OperationalEventLog();
        UUID runId = UUID.randomUUID();

        for (String bad : new String[] {"", " ", "Identity.login", "identity login", "identity.login\nforged",
                "1identity", "a".repeat(81)}) {
            assertThatThrownBy(() -> log.warn(bad, runId, TASK, INSTITUTION))
                    .as("event name %s", bad.length() > 20 ? "of length " + bad.length() : bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(logged.list).as("a rejected name writes nothing").isEmpty();
    }

    @Test
    void acceptsAnEventNameOfEightyCharacters() {
        new OperationalEventLog().warn("a" + "b".repeat(79), UUID.randomUUID(), TASK, INSTITUTION);

        assertThat(logged.list).hasSize(1);
    }

    @Test
    void rejectsATaskNameThatIsNotAClosedIdentifier() {
        OperationalEventLog log = new OperationalEventLog();

        for (String bad : new String[] {"", "Identity.purge", "identity purge", "identity.purge\nforged",
                "1identity", "-identity", "identity.purge=forged", "a".repeat(81)}) {
            assertThatThrownBy(() -> log.warn(EVENT, UUID.randomUUID(), bad, INSTITUTION))
                    .as("task name %s", bad.length() > 20 ? "of length " + bad.length() : bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(logged.list).as("a rejected task writes nothing").isEmpty();
    }

    @Test
    void acceptsTheTaskNamesOfTheIdentityPurges() {
        OperationalEventLog log = new OperationalEventLog();

        log.warn(EVENT, UUID.randomUUID(), "identity.purge-password-reset-tokens", INSTITUTION);
        log.warn(EVENT, UUID.randomUUID(), "identity.purge-refresh-tokens", INSTITUTION);

        assertThat(logged.list).hasSize(2);
    }

    @Test
    void rejectsNullArguments() {
        OperationalEventLog log = new OperationalEventLog();

        assertThatThrownBy(() -> log.warn(null, UUID.randomUUID(), TASK, INSTITUTION))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("event");
        assertThatThrownBy(() -> log.warn(EVENT, null, TASK, INSTITUTION))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("runId");
        assertThatThrownBy(() -> log.warn(EVENT, UUID.randomUUID(), null, INSTITUTION))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("taskName");
        assertThatThrownBy(() -> log.warn(EVENT, UUID.randomUUID(), TASK, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("institutionId");
    }
}
