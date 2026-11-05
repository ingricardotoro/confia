package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.confia.shared.web.authorization.RouteCoverageGuard;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Scenario AZ36 (rbac-permission-matrix-and-audit-integration design.md D13): the administrative process
 * started by its production launcher, against a real PostgreSQL, has the guard of the start in its
 * context, started without it failing, and the routes it evaluated are not an empty set. The launcher
 * would have thrown at the start if one route were on no list, so reaching the assertions is already
 * the first half of the proof; the second half runs the guard again on the same context and reads the
 * routes the process serves.
 */
class ProductionRoutesClassifiedIT extends CommittingPostgresIntegrationTest {

    private static ConfigurableApplicationContext process;

    @BeforeAll
    static void startTheProcess() throws NoSuchAlgorithmException {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        process = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys);
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    @Test
    void theProcessStartedWithTheGuardInItsContextAndOverANonEmptySetOfRoutes() {
        List<String> routes = AdminProcessLauncher.routesOf(process);

        assertThat(process.getBeansOfType(RouteCoverageGuard.class)).hasSize(1);
        assertThat(routes).as("non-vacuous: the guard evaluated the routes the process serves")
                .contains("POST /api/v1/auth/sessions", "GET /api/v1/auth/sessions/current",
                        "POST /api/v1/auth/mfa/verifications")
                .hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void theGuardPassesAgainOverTheSameContext() {
        assertThatCode(() -> process.getBean(RouteCoverageGuard.class)
                .afterSingletonsInstantiated()).doesNotThrowAnyException();
    }
}
