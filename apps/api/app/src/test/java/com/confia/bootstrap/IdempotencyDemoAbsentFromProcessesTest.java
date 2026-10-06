package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.RegisteredRoutes.Route;
import com.confia.shared.web.idempotency.IdempotencyKeyInterceptor;
import com.confia.shared.web.idempotency.IdempotentRequestHandler;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Specs/web-edge, requirement "El controlador de demostración de la idempotencia vive solo en el
 * árbol de pruebas" (web-edge-foundations design.md, decision 19). The administrative and the portal
 * process, started through their production entry point, register no route of the demonstration
 * controller, and the OpenAPI document of the administrative process, with the {@code local}
 * profile that serves it, does not name one.
 *
 * <p>The mechanism itself is not absent from the administrative process: its interceptor and its
 * handler are registered there and nowhere else, which is what makes the absence of the route mean
 * something. Both processes start with no database, so this is a {@code *Test}.
 */
class IdempotencyDemoAbsentFromProcessesTest {

    private static final String DEMO_ROUTE_PREFIX = "/test/idempotency";

    @Test
    void theAdministrativeProcessRegistersTheMechanismButNoRouteThatAppliesIt() {
        try (OpenApiProcess admin = OpenApiProcess.start("admin", "local")) {
            assertThat(admin.context().getBeansOfType(IdempotencyKeyInterceptor.class))
                    .as("non-vacuous: the mechanism is wired in this process").hasSize(1);
            assertThat(admin.context().getBeansOfType(IdempotentRequestHandler.class)).hasSize(1);
            assertThat(RegisteredRoutes.of(admin.context()))
                    .extracting(Route::pattern)
                    .noneMatch(pattern -> pattern.startsWith(DEMO_ROUTE_PREFIX));
            assertThat(admin.context().getBeanNamesForType(demoController())).isEmpty();

            HttpResponse<String> document = admin.get(OpenApiProcess.API_DOCS_PATH);
            assertThat(document.statusCode()).isEqualTo(200);
            assertThat(document.body()).as("non-vacuous: the document is the real one")
                    .contains("\"openapi\"").doesNotContain(DEMO_ROUTE_PREFIX);
        }
    }

    @Test
    void thePortalProcessHasNeitherTheMechanismNorTheDemonstrationRoute() {
        try (OpenApiProcess portal = OpenApiProcess.start("portal", "local")) {
            assertThat(portal.context().getBeansOfType(IdempotencyKeyInterceptor.class)).isEmpty();
            assertThat(portal.context().getBeansOfType(IdempotentRequestHandler.class)).isEmpty();
            List<Route> routes = RegisteredRoutes.of(portal.context());
            assertThat(routes).extracting(Route::pattern)
                    .noneMatch(pattern -> pattern.startsWith(DEMO_ROUTE_PREFIX));
        }
    }

    private static Class<?> demoController() {
        try {
            return Class.forName("com.confia.shared.web.idempotency.IdempotencyDemoController");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("the demonstration controller is in the test tree, which "
                    + "this test runs in", e);
        }
    }
}
