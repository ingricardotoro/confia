package com.confia.bootstrap;

import static com.confia.bootstrap.BootFailureAssertions.assertNoSecretFragment;
import static com.confia.bootstrap.BootFailureAssertions.chainOf;
import static com.confia.bootstrap.BootFailureAssertions.stackTraceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.shared.security.csrf.CsrfTokens;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Specs/web-edge, scenarios WE59 and WE60, and specs/build-integrity, BI58 (session-endpoints-
 * cookie-and-csrf design.md, decision 3): the administrative process starts with a CSRF key made
 * by the test and refuses to start without it, with a key shorter than 32 bytes, with a key that is
 * not Base64 or with a previous key equal to the current one; the portal and the worker hold no CSRF
 * component and abort when either property is in their environment. Every case starts the real
 * process through the production launcher and needs no Docker. A refusal names the property and
 * repeats no part of the value.
 */
class CsrfKeyStartupTest {

    private static final String GUARD = "SigningKeyPlacementGuard";

    /** Not a secret and not valid Base64: symbols, so a fragment found in a trace can only come from it. */
    private static final String MARKER = "Qx7!Rk2#Jv9%Wm5@";

    @Test
    void theAdministrativeProcessWithoutTheKeyDoesNotStartAndNamesTheProperty() {
        assertThatThrownBy(() -> launch("admin",
                TestProcessArguments.adminWithout(TestProcessArguments.CSRF_KEY_PROPERTY)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure)).contains(TestProcessArguments.CSRF_KEY_PROPERTY);
                    assertThat(chainOf(failure)).anyMatch(IllegalStateException.class::isInstance);
                    assertNoSecretFragment(failure, "");
                });
    }

    @Test
    void theAdministrativeProcessWithAKeyOf16BytesDoesNotStartAndRepeatsNothingOfIt() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> launch("admin", TestProcessArguments.adminWith(
                TestProcessArguments.CSRF_KEY_PROPERTY, shortKey)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure)).contains(TestProcessArguments.CSRF_KEY_PROPERTY);
                    assertThat(chainOf(failure)).anyMatch(IllegalStateException.class::isInstance);
                    assertNoSecretFragment(failure, shortKey);
                });
    }

    @Test
    void theAdministrativeProcessWithAKeyThatIsNotBase64DoesNotStartAndRepeatsNothingOfIt() {
        assertThatThrownBy(() -> launch("admin", TestProcessArguments.adminWith(
                TestProcessArguments.CSRF_KEY_PROPERTY, MARKER)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure)).contains(TestProcessArguments.CSRF_KEY_PROPERTY);
                    assertNoSecretFragment(failure, MARKER);
                });
    }

    @Test
    void theAdministrativeProcessWithAPreviousKeyEqualToTheCurrentOneDoesNotStart() {
        String key = TestProcessArguments.newCsrfKey();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put(TestProcessArguments.CSRF_KEY_PROPERTY, key);
        overrides.put(TestProcessArguments.CSRF_PREVIOUS_KEY_PROPERTY, key);

        assertThatThrownBy(() -> launch("admin", TestProcessArguments.adminWith(overrides)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure))
                            .contains(TestProcessArguments.CSRF_PREVIOUS_KEY_PROPERTY);
                    assertNoSecretFragment(failure, key);
                });
    }

    @Test
    void theAdministrativeProcessWithAPreviousKeyThatIsNotBase64DoesNotStartAndNamesIt() {
        assertThatThrownBy(() -> launch("admin", TestProcessArguments.adminWith(
                TestProcessArguments.CSRF_PREVIOUS_KEY_PROPERTY, MARKER)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure))
                            .contains(TestProcessArguments.CSRF_PREVIOUS_KEY_PROPERTY);
                    assertNoSecretFragment(failure, MARKER);
                });
    }

    @Test
    void theAdministrativeProcessStartsWithAKeyGeneratedByTheTestAndHoldsTheTokenComponent() {
        LaunchOutcome outcome = launch("admin", TestProcessArguments.forProcess("admin"));
        try {
            assertThat(outcome.context()).isNotNull();
            CsrfTokens tokens = outcome.context().getBean(CsrfTokens.class);
            assertThat(tokens.verify(tokens.issue("a-refresh-token"), "a-refresh-token")).isTrue();
        } finally {
            close(outcome.context());
        }
    }

    @Test
    void theAdministrativeProcessStartsWithADifferentPreviousKey() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put(TestProcessArguments.CSRF_PREVIOUS_KEY_PROPERTY,
                TestProcessArguments.newCsrfKey());
        LaunchOutcome outcome = launch("admin", TestProcessArguments.adminWith(overrides));
        try {
            assertThat(outcome.context().getBeansOfType(CsrfTokens.class)).hasSize(1);
        } finally {
            close(outcome.context());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"portal", "worker"})
    void theOtherProcessesStartWithoutTheKeysAndHoldNoCsrfComponent(String process) {
        LaunchOutcome outcome = launch(process, TestProcessArguments.forProcess(process));
        try {
            assertThat(outcome.context()).isNotNull();
            assertThat(outcome.context().getBeansOfType(CsrfTokens.class))
                    .as("no CSRF component in the %s process", process).isEmpty();
        } finally {
            close(outcome.context());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"portal", "worker"})
    void theOtherProcessesAbortWhenTheCurrentCsrfKeyIsPresent(String process) {
        assertAbortsBeforeTheContext(process, TestProcessArguments.CSRF_KEY_PROPERTY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"portal", "worker"})
    void theOtherProcessesAbortWhenThePreviousCsrfKeyIsPresent(String process) {
        assertAbortsBeforeTheContext(process, TestProcessArguments.CSRF_PREVIOUS_KEY_PROPERTY);
    }

    private static void assertAbortsBeforeTheContext(String process, String property) {
        assertThatThrownBy(() -> launch(process,
                TestProcessArguments.forProcess(process, "--" + property + "=" + MARKER)))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure)).contains(property).contains(GUARD);
                    assertNoSecretFragment(failure, MARKER);
                });
    }

    private static LaunchOutcome launch(String process, String[] arguments) {
        return ConfiaApplication.launch(arguments, process);
    }

    private static void close(ConfigurableApplicationContext context) {
        if (context != null) {
            context.close();
        }
    }
}
