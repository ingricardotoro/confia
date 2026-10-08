package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.shared.web.authentication.AccessTokenAuthenticationFilter;

/**
 * One test per {@link AppProfile} value, plus the invalid/missing case (task 3.1). Admin and
 * portal bind to a random port ({@code --server.port=0}) to avoid a fixed-port conflict in CI.
 */
class ConfiaApplicationTest {

    @Test
    void startsTheAdminWebContextOnTheAdminProfile() {
        LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.forProcess("admin"), "admin");
        try {
            assertThat(outcome.exitCode()).isZero();
            assertThat(outcome.context()).isNotNull();
            assertThat(outcome.context().isActive()).isTrue();
            assertThat(outcome.context()).isInstanceOf(WebServerApplicationContext.class);
        } finally {
            close(outcome.context());
        }
    }

    @Test
    void startsThePortalWebContextOnThePortalProfile() {
        LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.forProcess("portal"), "portal");
        try {
            assertThat(outcome.exitCode()).isZero();
            assertThat(outcome.context()).isNotNull();
            assertThat(outcome.context().isActive()).isTrue();
            assertThat(outcome.context()).isInstanceOf(WebServerApplicationContext.class);
        } finally {
            close(outcome.context());
        }
    }

    @Test
    void startsTheWorkerContextWithoutHttpOnTheWorkerProfile() {
        LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.forProcess("worker"), "worker");
        try {
            assertThat(outcome.exitCode()).isZero();
            assertThat(outcome.context()).isNotNull();
            assertThat(outcome.context().isActive()).isTrue();
            assertThat(outcome.context()).isNotInstanceOf(WebServerApplicationContext.class);
        } finally {
            close(outcome.context());
        }
    }

    @Test
    void theAdminChainAuthenticatesBearerTokensAndThePortalChainDoesNot() {
        LaunchOutcome admin = ConfiaApplication.launch(TestProcessArguments.forProcess("admin"),
                "admin");
        try {
            assertThat(admin.context().getBean(SecurityFilterChain.class).getFilters())
                    .hasAtLeastOneElementOfType(AccessTokenAuthenticationFilter.class);
        } finally {
            close(admin.context());
        }
        LaunchOutcome portal = ConfiaApplication.launch(TestProcessArguments.forProcess("portal"),
                "portal");
        try {
            assertThat(portal.context().getBean(SecurityFilterChain.class).getFilters())
                    .isNotEmpty()
                    .doesNotHaveAnyElementsOfTypes(AccessTokenAuthenticationFilter.class);
        } finally {
            close(portal.context());
        }
    }

    @Test
    void recognizesMigrateWithoutStartingAnyContext() {
        LaunchOutcome outcome = ConfiaApplication.launch(new String[0], "migrate");

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.context()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "ADMIN", "administrator", "unknown"})
    void abortsWithANonZeroExitCodeOnAnUnknownProfile(String rawProfile) {
        LaunchOutcome outcome = ConfiaApplication.launch(new String[0], rawProfile);

        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.context()).isNull();
    }

    @Test
    void abortsWithANonZeroExitCodeWhenAppProfileIsAbsent() {
        LaunchOutcome outcome = ConfiaApplication.launch(new String[0], null);

        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.context()).isNull();
    }

    private static void close(ConfigurableApplicationContext context) {
        if (context != null) {
            context.close();
        }
    }
}
