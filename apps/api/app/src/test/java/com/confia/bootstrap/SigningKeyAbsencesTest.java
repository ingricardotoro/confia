package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.RegisteredRoutes.Route;
import com.confia.shared.security.token.SigningKeyRing;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;

/**
 * The two absences of specs/identity, "Ausencia del endpoint JWKS y del par de claves del portal"
 * (design.md, decision 3: the only verifier is the administrative process, with the ring in memory;
 * D-N2: the portal's private key name is only reserved). Each is true until the change that ends
 * it: the first external verifier brings a key route, and the first portal route brings the
 * portal's own pair. The absence is shown on started processes, with a route that would exist had
 * one been written and a ring that would exist had one been registered.
 */
class SigningKeyAbsencesTest {

    private static final String TOKEN_PACKAGE = "com.confia.shared.security.token";

    @Test
    void theAdministrativeRouteMapHasNoPublicKeyRoute() {
        // The local profile is the one that registers routes (the OpenAPI surface), so the map is
        // not empty and the absence is not the absence of every route.
        try (OpenApiProcess admin = OpenApiProcess.start("admin", "local")) {
            List<Route> routes = RegisteredRoutes.of(admin.context());

            assertThat(routes).anyMatch(route -> route.pattern().contains("api-docs"));
            assertThat(routes).noneMatch(route -> isAKeyRoute(route.pattern()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/.well-known/jwks.json", "/.well-known/openid-configuration",
            "/jwks.json", "/api/v1/auth/keys"})
    void aRequestForAPublicKeyRouteIsAnsweredWithAnUnauthorizedStatus(String path) {
        try (OpenApiProcess admin = OpenApiProcess.start("admin")) {
            assertThat(admin.get(path).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void thePortalHasNoKeyRingAndNoBeanOfTheTokenPackage() {
        try (OpenApiProcess portal = OpenApiProcess.start("portal")) {
            ApplicationContext context = portal.context();

            assertThat(context.getBeansOfType(SigningKeyRing.class)).isEmpty();
            assertThat(Arrays.stream(context.getBeanDefinitionNames())
                    .map(context::getType)
                    .filter(type -> type != null)
                    .map(Class::getName))
                    .as("the portal context holds beans, none of them from the token package")
                    .isNotEmpty()
                    .noneMatch(name -> name.startsWith(TOKEN_PACKAGE + "."));
        }
    }

    private static boolean isAKeyRoute(String pattern) {
        String lower = pattern.toLowerCase(Locale.ROOT);
        return lower.contains("jwks") || lower.contains("well-known") || lower.contains("keys");
    }
}
