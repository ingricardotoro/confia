package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The declaration of a one-time job type and the rule for task names (background-jobs-with-db-scheduler
 * design.md decision 6): module-qualified, lowercase words, because the name is the {@code task_name}
 * of the table and its first segment is the module that owns the job.
 */
class JobTypeTest {

    record Payload(UUID id) { }

    private static final RetryPolicy RETRY =
            new RetryPolicy(3, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(5));

    @ParameterizedTest
    @ValueSource(strings = {"identity.password-reset-issuance", "shared.purge-idempotency-keys", "a.b",
            "identity.a1.b2-c3"})
    void acceptsAModuleQualifiedLowercaseName(String name) {
        JobType<Payload> type = new JobType<>(name, Payload.class, RETRY);

        assertThat(type.name()).isEqualTo(name);
        assertThat(type.payloadClass()).isEqualTo(Payload.class);
        assertThat(type.retry()).isSameAs(RETRY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "single", "Identity.job", "identity.Job", "identity..job", ".identity.job",
            "identity.job.", "identity.job name", "identity.job_name", "1identity.job", "identity.1job",
            "-identity.job", "identity.-job", "identity.job\n", "identity.job\nother.job"})
    void rejectsAnyOtherName(String name) {
        assertThatThrownBy(() -> new JobType<>(name, Payload.class, RETRY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresItsThreeParts() {
        assertThatThrownBy(() -> new JobType<>(null, Payload.class, RETRY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobType<>("identity.job", null, RETRY))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("payloadClass");
        assertThatThrownBy(() -> new JobType<>("identity.job", Payload.class, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("retry");
    }
}
