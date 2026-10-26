package com.confia.bootstrap;

import static com.confia.bootstrap.BootFailureAssertions.stackTraceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.shared.jobs.infrastructure.ConfiguredInstitutions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Scenarios BJ31 and BJ64 (background-jobs-with-db-scheduler design.md decision 10, P5): the real
 * worker does not start without an explicit list of institutions, nor with an invalid or repeated
 * entry, and the failure names the property and the position without repeating the list; with a
 * valid list it starts and holds the list in order.
 */
class ConfiguredInstitutionsStartupTest {

    private static final String PROPERTY = "confia.jobs.institutions";
    private static final String VALID = "11111111-1111-4111-8111-111111111111";

    /** The worker arguments with {@code PROPERTY} replaced by {@code value}, or removed if null. */
    private static String[] workerWith(String value) {
        List<String> arguments = new ArrayList<>();
        for (String argument : TestProcessArguments.forProcess("worker")) {
            if (!argument.startsWith("--" + PROPERTY + "=")) {
                arguments.add(argument);
            }
        }
        if (value != null) {
            arguments.add("--" + PROPERTY + "=" + value);
        }
        return arguments.toArray(String[]::new);
    }

    @Test
    void theWorkerDoesNotStartWithoutThePropertyAndTheFailureNamesIt() {
        assertThatThrownBy(() -> ConfiaApplication.launch(workerWith(null), "worker"))
                .satisfies(failure -> assertThat(stackTraceOf(failure)).contains(PROPERTY)
                        .contains("is required"));
    }

    @Test
    void theWorkerDoesNotStartWithAnEmptyList() {
        assertThatThrownBy(() -> ConfiaApplication.launch(workerWith(" , "), "worker"))
                .satisfies(failure -> assertThat(stackTraceOf(failure)).contains(PROPERTY)
                        .contains("at least one"));
    }

    @Test
    void theWorkerDoesNotStartWithAnInvalidEntryAndTheFailureNamesItsPosition() {
        String list = VALID + ",no-es-uuid";

        assertThatThrownBy(() -> ConfiaApplication.launch(workerWith(list), "worker"))
                .satisfies(failure -> assertThat(stackTraceOf(failure)).contains(PROPERTY)
                        .contains("entry 2").doesNotContain(VALID).doesNotContain("no-es-uuid"));
    }

    @Test
    void theWorkerDoesNotStartWithARepeatedEntryAndTheFailureNamesItsPosition() {
        String list = VALID + "," + VALID;

        assertThatThrownBy(() -> ConfiaApplication.launch(workerWith(list), "worker"))
                .satisfies(failure -> assertThat(stackTraceOf(failure)).contains(PROPERTY)
                        .contains("entry 2").contains("entry 1"));
    }

    @Test
    void theWorkerStartsWithAValidListAndHoldsItInOrder() {
        String second = UUID.randomUUID().toString();
        LaunchOutcome outcome = ConfiaApplication.launch(workerWith(VALID + "," + second), "worker");
        ConfigurableApplicationContext context = outcome.context();
        assertThat(context).as("the worker must start").isNotNull();
        try {
            assertThat(context.getBean(ConfiguredInstitutions.class).institutions())
                    .extracting(id -> id.value().toString()).containsExactly(VALID, second);
        } finally {
            context.close();
        }
    }
}
