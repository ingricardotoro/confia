package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.architecture.fixture.sessionroutes.BadMfaDisableController;
import com.confia.architecture.fixture.sessionroutes.BadSessionListingController;
import com.confia.shared.web.authentication.AuthenticatedEndpoint;
import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.web.authentication.SessionCheck;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * Specs/identity, scenario [I216]: no route accepts a complete access token to enroll or to remove a
 * second factor, and none lists the sessions of an account. Both are gaps with an owner (change 8 and
 * the first change of {@code apps/admin-web}) and stop being true the day that owner delivers them.
 *
 * <p>The routes are read from the handlers of the compiled production classes and from the two
 * lists of the administrative chain. The routes of the second factor are exactly the two restricted
 * ones, and neither is on the list of routes that take an access token. Each absence has its
 * detection fixture, a deliberately violating class of {@code architecture.fixture.sessionroutes}
 * that the same detector must find, and the base set is proven to hold the real routes first.
 */
class NoFullSessionMfaRoutesTest {

    private static final String MFA = "/api/v1/auth/mfa";
    private static final List<String> RESTRICTED_ROUTES = List.of("POST " + MFA + "/enrollments",
            "POST " + MFA + "/verifications");
    private static final String SESSIONS = "/api/v1/auth/sessions";

    @Test
    void theRealRoutesAreScannedAndTheSecondFactorOnesAreExactlyTheTwoRestrictedRoutes() {
        List<String> routes = RouteInventory.routesOf(productionClasses());

        assertThat(routes).as("the scan sees the real routes, so an absence below is not vacuous")
                .contains("POST " + SESSIONS, "POST " + SESSIONS + "/refresh",
                        "GET " + SESSIONS + "/current", "DELETE " + SESSIONS + "/current",
                        "DELETE " + SESSIONS);
        assertThat(secondFactorRoutes(routes)).containsExactlyInAnyOrderElementsOf(RESTRICTED_ROUTES);
        assertThat(sessionListings(routes)).isEmpty();
    }

    @Test
    void noRouteThatTakesACompleteAccessTokenEnrollsOrRemovesASecondFactorOrListsSessions() {
        AuthenticatedEndpoints production = AuthenticatedEndpoints.forAdmin();

        assertThat(production.endpoints()).as("the list of access-token routes is not empty")
                .isNotEmpty();
        assertThat(accessTokenRoutesViolations(production)).isEmpty();
        assertThat(production.secondFactorEndpoints()).extracting(
                endpoint -> endpoint.method() + " " + endpoint.pattern())
                .containsExactlyInAnyOrderElementsOf(RESTRICTED_ROUTES);
    }

    @Test
    void theDetectorFindsFixturesThatListSessionsAndThatEnrollOrRemoveASecondFactor() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(BadSessionListingController.class,
                BadMfaDisableController.class);
        List<String> routes = RouteInventory.routesOf(fixtures);

        assertThat(sessionListings(routes)).containsExactly("GET " + SESSIONS);
        assertThat(secondFactorRoutes(routes)).containsExactlyInAnyOrder("DELETE " + MFA,
                "POST " + MFA + "/totp-secrets");
    }

    @Test
    void theDetectorFindsAnAccessTokenRouteThatEnrollsRemovesOrLists() {
        AuthenticatedEndpoints withEnrollment = new AuthenticatedEndpoints(List.of(
                new AuthenticatedEndpoint(HttpMethod.POST, MFA + "/enrollments",
                        SessionCheck.LIVE_SESSION)));
        AuthenticatedEndpoints withRemoval = new AuthenticatedEndpoints(List.of(
                new AuthenticatedEndpoint(HttpMethod.DELETE, MFA, SessionCheck.LIVE_SESSION)));
        AuthenticatedEndpoints withListing = new AuthenticatedEndpoints(List.of(
                new AuthenticatedEndpoint(HttpMethod.GET, SESSIONS, SessionCheck.LIVE_SESSION)));

        assertThat(accessTokenRoutesViolations(withEnrollment)).containsExactly(
                "POST " + MFA + "/enrollments takes a complete access token");
        assertThat(accessTokenRoutesViolations(withRemoval)).containsExactly(
                "DELETE " + MFA + " takes a complete access token");
        assertThat(accessTokenRoutesViolations(withListing)).containsExactly(
                "GET " + SESSIONS + " lists sessions");
    }

    // ---------------------------------------------------------------------------------------------

    private static List<String> secondFactorRoutes(List<String> routes) {
        return routes.stream().filter(route -> route.contains(" " + MFA)).toList();
    }

    private static List<String> sessionListings(List<String> routes) {
        return routes.stream().filter(route -> route.equals("GET " + SESSIONS)).toList();
    }

    /** The routes that take an access token and have to do with a second factor, or list sessions. */
    private static List<String> accessTokenRoutesViolations(AuthenticatedEndpoints endpoints) {
        List<String> violations = new ArrayList<>();
        for (AuthenticatedEndpoint endpoint : endpoints.endpoints()) {
            String route = endpoint.method() + " " + endpoint.pattern();
            if (endpoint.pattern().startsWith(MFA)) {
                violations.add(route + " takes a complete access token");
            }
            if (endpoint.method() == HttpMethod.GET && endpoint.pattern().equals(SESSIONS)) {
                violations.add(route + " lists sessions");
            }
        }
        return violations;
    }
}
