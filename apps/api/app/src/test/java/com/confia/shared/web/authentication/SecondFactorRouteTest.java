package com.confia.shared.web.authentication;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.token.MfaPurpose;
import com.confia.shared.web.harness.HarnessProcess;
import com.confia.shared.web.harness.HarnessTokens;
import com.confia.shared.web.harness.RecordingSessionValidity;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge, "Las rutas restringidas aceptan solo un token restringido de su uso y su principal
 * es de otro tipo que {@code AuthenticatedActor}" (session-endpoints-cookie-and-csrf design.md,
 * decision 6): the real chain and the real filter behind the database-free harness, over HTTP, with
 * one restricted route for {@code mfa-verify} and {@code mfa-enroll} and another for {@code
 * mfa-enroll} alone. Each token opens only its own routes, a restricted token never becomes the
 * session actor, and every other token is refused by its audience.
 */
class SecondFactorRouteTest {

    private static final String VERIFY_ROUTE = "/test/second-factor/verify";
    private static final String ENROLL_ROUTE = "/test/second-factor/enroll";
    private static final String INVALID = "Bearer error=\"invalid_token\"";

    private static HarnessProcess process;

    private int secondFactorBefore;
    private int authenticatedBefore;
    private int validityBefore;

    @BeforeAll
    static void start() {
        process = HarnessProcess.start();
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    @BeforeEach
    void remember() {
        process.bean(RecordingSessionValidity.class).answer(true);
        secondFactorBefore = process.calls().secondFactorInvocations();
        authenticatedBefore = process.calls().authenticatedInvocations();
        validityBefore = process.sessionValidity().invocations();
    }

    private static HttpResponse<String> post(String path, String token) {
        return process.send("POST", path, "Origin", HarnessProcess.ALLOWED_ORIGIN, "Authorization",
                "Bearer " + token);
    }

    private static void assertTokenInvalid(HttpResponse<String> response) {
        assertProblem(response, 401, "token-invalid");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(INVALID);
    }

    // --- WE66: the restricted principal, and no actor ---

    @Test
    void aRestrictedTokenBindsTheRestrictedPrincipalAndNeverTheSessionActor() {
        HarnessTokens tokens = process.tokens();

        HttpResponse<String> response = post(VERIFY_ROUTE, tokens.restricted(MfaPurpose.MFA_VERIFY));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = HarnessProcess.json(response);
        assertThat(body.get("pendingPresent").asBoolean()).isTrue();
        assertThat(body.get("purpose").asString()).isEqualTo("VERIFY");
        assertThat(body.get("accountId").asString()).isEqualTo(HarnessTokens.ACCOUNT.toString());
        assertThat(body.get("institutionId").asString())
                .isEqualTo(HarnessTokens.INSTITUTION.toString());
        assertThat(body.get("actorPresent").asBoolean())
                .as("AuthenticatedActor.current() stays empty behind a restricted token").isFalse();
        assertThat(body.get("springPrincipal").asString()).isEqualTo("SecondFactorAuthentication");
        assertThat(body.get("springAuthenticated").asBoolean()).isTrue();
        assertThat(body.get("institutionProvider").asString())
                .as("the institution provider reads the actor, so it refuses").isEqualTo("refused");
        assertThat(process.sessionValidity().invocations())
                .as("the session-validity port is never asked about a restricted token")
                .isEqualTo(validityBefore);
    }

    @Test
    void anEnrollTokenBindsThePurposeEnroll() {
        HttpResponse<String> response = post(ENROLL_ROUTE,
                process.tokens().restricted(MfaPurpose.MFA_ENROLL));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(response).get("purpose").asString()).isEqualTo("ENROLL");
    }

    // --- WE67: each token opens only its own routes ---

    @ParameterizedTest
    @CsvSource({
            "ACCESS, /test/whoami, 200",
            "ACCESS, /test/second-factor/verify, 401",
            "ACCESS, /test/second-factor/enroll, 401",
            "VERIFY, /test/whoami, 401",
            "VERIFY, /test/second-factor/verify, 200",
            "VERIFY, /test/second-factor/enroll, 401",
            "ENROLL, /test/whoami, 401",
            "ENROLL, /test/second-factor/verify, 200",
            "ENROLL, /test/second-factor/enroll, 200"})
    void eachTokenOpensOnlyItsOwnRoutes(String kind, String path, int expected) {
        HarnessTokens tokens = process.tokens();
        String token = switch (kind) {
            case "ACCESS" -> tokens.access();
            case "VERIFY" -> tokens.restricted(MfaPurpose.MFA_VERIFY);
            default -> tokens.restricted(MfaPurpose.MFA_ENROLL);
        };
        boolean restrictedRoute = path.startsWith("/test/second-factor");

        HttpResponse<String> response = restrictedRoute ? post(path, token)
                : process.get(path, "Authorization", "Bearer " + token);

        if (expected == 200) {
            assertThat(response.statusCode()).isEqualTo(200);
        } else {
            assertTokenInvalid(response);
        }
    }

    // --- WE69 and WE09: a restricted token authorizes nothing outside its routes ---

    @Test
    void aVerifyTokenOnTheCloseAllRouteAndOnAnUnregisteredRouteIsTokenInvalid() {
        String token = process.tokens().restricted(MfaPurpose.MFA_VERIFY);

        assertTokenInvalid(process.send("DELETE", "/test/sessions", "Origin",
                HarnessProcess.ALLOWED_ORIGIN, "Authorization", "Bearer " + token));
        assertTokenInvalid(post("/test/not-registered", token));
        assertThat(process.calls().authenticatedInvocations()).isEqualTo(authenticatedBefore);
        assertThat(process.calls().secondFactorInvocations()).isEqualTo(secondFactorBefore);
    }

    @Test
    void aRestrictedTokenIsRefusedOnAnAuthenticatedRouteOnARouteOnNeitherListAndOnAnUnregisteredOne() {
        for (MfaPurpose purpose : MfaPurpose.values()) {
            String token = process.tokens().restricted(purpose);
            String header = "Bearer " + token;

            assertTokenInvalid(process.get("/test/whoami", "Authorization", header));
            assertTokenInvalid(process.get("/x", "Authorization", header));
            assertTokenInvalid(process.get("/test/not-registered", "Authorization", header));
        }
        assertThat(process.calls().authenticatedInvocations()).isEqualTo(authenticatedBefore);
        assertThat(process.calls().protectedInvocations()).isZero();
    }

    @Test
    void theRestrictedTokenIsRefusedByItsAudienceNotByAClaimOfUse() {
        // A token with the administrative audience and the restricted claim of use is refused too,
        // by the closed list of claims of the access verifier: the audience is what separates them.
        String forged = process.tokens().withPermissions();

        assertTokenInvalid(process.get("/test/whoami", "Authorization", "Bearer " + forged));
        assertTokenInvalid(post(VERIFY_ROUTE, forged));
    }

    // --- WE10: the portal's token opens nothing ---

    @Test
    void thePortalTokenIsTokenInvalidOnAnAuthenticatedAndOnARestrictedRoute() {
        String portal = process.tokens().signedByThePortal();

        assertTokenInvalid(process.get("/test/whoami", "Authorization", "Bearer " + portal));
        assertTokenInvalid(post(VERIFY_ROUTE, portal));
        assertTokenInvalid(post(ENROLL_ROUTE, portal));
        assertThat(process.calls().secondFactorInvocations()).isEqualTo(secondFactorBefore);
    }

    // --- The causes the client can act on, and the anonymous request ---

    @Test
    void anExpiredRestrictedTokenIsTokenExpiredOnARestrictedRoute() {
        String expired = process.tokens().restrictedAt(MfaPurpose.MFA_VERIFY,
                Instant.now().minus(1, ChronoUnit.HOURS));

        HttpResponse<String> response = post(VERIFY_ROUTE, expired);

        assertProblem(response, 401, "token-expired");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(INVALID);
        assertThat(process.calls().secondFactorInvocations()).isEqualTo(secondFactorBefore);
    }

    @Test
    void aRestrictedRouteWithoutACredentialIsTheUniformDenial() {
        HttpResponse<String> response = process.send("POST", VERIFY_ROUTE, "Origin",
                HarnessProcess.ALLOWED_ORIGIN);

        assertProblem(response, 401, "authentication-required");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
        assertThat(process.calls().secondFactorInvocations()).isEqualTo(secondFactorBefore);
    }

    @Test
    void aRestrictedRouteServesOnlyItsMethod() {
        String token = process.tokens().restricted(MfaPurpose.MFA_ENROLL);

        HttpResponse<String> response = process.get(VERIFY_ROUTE, "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).as("a GET is not on the restricted list: the chain denies it")
                .isIn(401, 403);
        assertThat(process.calls().secondFactorInvocations()).isEqualTo(secondFactorBefore);
    }

    /** The restricted principal is a type of its own: the same route reports no actor for any purpose. */
    @Test
    void noPurposeEverPopulatesTheSessionActor() {
        for (MfaPurpose purpose : MfaPurpose.values()) {
            Function<String, HttpResponse<String>> call = path -> post(path,
                    process.tokens().restricted(purpose));

            HttpResponse<String> response = call.apply(VERIFY_ROUTE);

            assertThat(HarnessProcess.json(response).get("actorPresent").asBoolean()).isFalse();
        }
    }
}
