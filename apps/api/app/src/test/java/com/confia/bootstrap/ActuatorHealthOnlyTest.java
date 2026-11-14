package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.bootstrap.RegisteredRoutes.Route;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.endpoint.ExposableEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Actuator serves health and nothing else (dockerfiles-and-local-compose scenarios CR15, BJ75; probe P-2
 * of design.md decision 4). The check runs against the real administrative and portal contexts and has a
 * permanent fixture: a context that exposes one more endpoint, which the same check must name, so that a
 * check that stopped looking could not pass for lack of anything to find.
 */
class ActuatorHealthOnlyTest {

    private static final Set<String> HEALTH_ONLY = Set.of("health");

    static Set<String> exposedEndpointIds(ConfigurableApplicationContext context) {
        Set<String> ids = new TreeSet<>();
        context.getBean(WebEndpointsSupplier.class).getEndpoints().stream().map(ExposableEndpoint::getEndpointId)
                .forEach(id -> ids.add(id.toString()));
        return ids;
    }

    static Set<String> healthGroups(ConfigurableApplicationContext context) {
        return new TreeSet<>(context.getBean(HealthEndpointGroups.class).getNames());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin", "portal"})
    void theOnlyActuatorEndpointOfAWebProcessIsHealthWithAGroupForLivenessAndOneForReadiness(String process) {
        LaunchOutcome outcome = ConfiaApplication.launch(TestProcessArguments.forProcess(process), process);
        ConfigurableApplicationContext context = outcome.context();
        assertThat(context).as("the %s process must start", process).isNotNull();
        try {
            assertThat(exposedEndpointIds(context)).as("non-vacuous and exact: %s", process)
                    .containsExactlyInAnyOrderElementsOf(HEALTH_ONLY);
            assertThat(healthGroups(context)).containsExactly("liveness", "readiness");
        } finally {
            context.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin", "portal"})
    void theHealthRoutesAreTheExactPatternsOnTheMainPortAndNoActuatorRouteExists(String process) {
        LaunchOutcome outcome = ConfiaApplication.launch(TestProcessArguments.forProcess(process), process);
        ConfigurableApplicationContext context = outcome.context();
        assertThat(context).as("the %s process must start", process).isNotNull();
        try {
            List<Route> routes = RegisteredRoutes.of(context);
            assertThat(routes.stream().map(Route::toString)).contains("GET /health/live", "GET /health/ready");
            assertThat(routes.stream().map(Route::pattern)).as("no /actuator/** mapping (P-2)")
                    .noneMatch(pattern -> pattern.contains("actuator"));
        } finally {
            context.close();
        }
    }

    @Test
    void aContextThatExposesAnotherEndpointIsReported() {
        LaunchOutcome outcome = ConfiaApplication.launch(TestProcessArguments.forProcess("admin",
                "--management.endpoints.web.exposure.include=health,info",
                "--management.endpoints.access.default=read-only"), "admin");
        ConfigurableApplicationContext context = outcome.context();
        assertThat(context).as("the fixture process must start").isNotNull();
        try {
            assertThat(exposedEndpointIds(context)).contains("info").isNotEqualTo(HEALTH_ONLY);
        } finally {
            context.close();
        }
    }
}
