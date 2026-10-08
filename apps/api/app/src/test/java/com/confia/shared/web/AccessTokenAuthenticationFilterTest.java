package com.confia.shared.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import com.confia.shared.web.harness.HarnessTokens;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge, the bearer authentication of the administrative chain (session-tokens-and-web-layer
 * design.md, decision 5): the real chain and the real filter behind the database-free harness, over
 * HTTP. A credential is read from {@code Authorization: Bearer} and from nowhere else; a credential
 * that is present and broken is {@code 401} with the cause the client can act on, on a public route
 * as much as on a protected one; and nothing the filter does leaves a trace of the token.
 */
class AccessTokenAuthenticationFilterTest {

    private static final String INVALID = "Bearer error=\"invalid_token\"";

    private static HarnessProcess process;

    private int authenticatedBefore;
    private int openBefore;

    @BeforeEach
    void remember() {
        authenticatedBefore = process.calls().authenticatedInvocations();
        openBefore = process.calls().openInvocations();
    }

    @BeforeAll
    static void start() {
        process = HarnessProcess.start();
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static void assertInvalid(HttpResponse<String> response) {
        assertProblem(response, 401, "token-invalid");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(INVALID);
    }

    // --- No credential, or one that is not a bearer token ---

    @Test
    void noCredentialIsAnonymousAndGetsTheUniformDenialWithTheBearerChallenge() {
        HttpResponse<String> response = process.get("/test/whoami");

        assertProblem(response, 401, "authentication-required");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
        assertThat(process.calls().authenticatedInvocations()).isEqualTo(authenticatedBefore);
    }

    @Test
    void aBasicCredentialIsAnonymousAndNeverBecomesAnError() {
        HttpResponse<String> denied = process.get("/test/whoami", "Authorization", "Basic dXNlcjpwdw==");
        HttpResponse<String> open = process.get("/test/actor", "Authorization", "Basic dXNlcjpwdw==");

        assertProblem(denied, 401, "authentication-required");
        assertThat(open.statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(open).get("present").asBoolean()).isFalse();
    }

    @Test
    void withoutACredentialNoActorIsBound() {
        HttpResponse<String> response = process.get("/test/actor");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(response).get("present").asBoolean())
                .as("the route ran, and nobody was authenticated").isFalse();
    }

    // --- A bearer credential that is malformed ---

    @ParameterizedTest
    @ValueSource(strings = {"Bearer", "Bearer ", "Bearer  abc.def.ghi", "Bearer abc def",
            "Bearer abc.def.ghi extra", "bearer", "BEARER ", "Bearer a b c", ""})
    void aMalformedBearerHeaderIsTokenInvalid(String header) {
        assertInvalid(process.get("/test/whoami", "Authorization", header));
    }

    @Test
    void aTabInPlaceOfTheSpaceAfterBearerIsTokenInvalid() {
        assertInvalid(process.get("/test/whoami", "Authorization",
                "Bearer\t" + process.tokens().access()));
        assertThat(process.calls().authenticatedInvocations()).isEqualTo(authenticatedBefore);
    }

    @Test
    void anEmptyAuthorizationHeaderIsTokenInvalidOnAPublicRouteToo() {
        assertInvalid(process.get("/test/actor", "Authorization", ""));
    }

    @Test
    void twoAuthorizationHeadersAreTokenInvalidEvenWhenEachOneIsValid() {
        String token = process.tokens().access();

        assertInvalid(process.get("/test/whoami", "Authorization", bearer(token),
                "Authorization", bearer(token)));
        assertInvalid(process.get("/test/whoami", "Authorization", bearer(token),
                "Authorization", "Basic dXNlcjpwdw=="));
        assertThat(process.calls().authenticatedInvocations()).isEqualTo(authenticatedBefore);
    }

    @Test
    void theSchemeIsCaseInsensitive() {
        HttpResponse<String> response = process.get("/test/whoami", "Authorization",
                "bEaReR " + process.tokens().access());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    // --- A bearer credential the verifier refuses ---

    @Test
    void aTamperedTokenIsTokenInvalid() {
        String token = process.tokens().access();
        String[] segments = token.split("[.]");
        String payload = segments[1].substring(0, segments[1].length() - 2)
                + (segments[1].endsWith("AA") ? "BB" : "AA");

        assertInvalid(process.get("/test/whoami", "Authorization",
                bearer(segments[0] + "." + payload + "." + segments[2])));
        assertInvalid(process.get("/test/whoami", "Authorization", bearer("SECRETO.NO.TOKEN")));
    }

    @Test
    void aTokenOfAnotherKeyTheRestrictedTokenThePortalTokenAndAnExtraClaimAreTokenInvalid() {
        HarnessTokens tokens = process.tokens();

        assertInvalid(process.get("/test/whoami", "Authorization", bearer(tokens.signedByThePortal())));
        assertInvalid(process.get("/test/whoami", "Authorization", bearer(tokens.restricted())));
        assertInvalid(process.get("/test/whoami", "Authorization", bearer(tokens.withPermissions())));
        assertInvalid(process.get("/test/whoami", "Authorization",
                bearer(tokens.signedByAStranger())));
        assertThat(process.calls().authenticatedInvocations()).isEqualTo(authenticatedBefore);
    }

    @Test
    void anExpiredTokenWithAnIntactSignatureIsTokenExpired() {
        String expired = process.tokens().accessAt(Instant.now().minus(1, ChronoUnit.HOURS));

        HttpResponse<String> response = process.get("/test/whoami", "Authorization", bearer(expired));

        assertProblem(response, 401, "token-expired");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(INVALID);
        assertThat(process.calls().authenticatedInvocations()).isEqualTo(authenticatedBefore);
    }

    @Test
    void aMalleableOrShortSignatureIsA401AndNeverA500() {
        String token = process.tokens().access();

        assertInvalid(process.get("/test/whoami", "Authorization",
                bearer(HarnessTokens.malleable(token))));
        assertInvalid(process.get("/test/whoami", "Authorization",
                bearer(HarnessTokens.withShortSignature(token))));
        assertThat(process.get("/test/whoami", "Authorization", bearer(token)).statusCode())
                .as("non-vacuous: the unforged token is accepted").isEqualTo(200);
    }

    @Test
    void aBrokenCredentialOnAPublicRouteIsStill401WithTheCause() {
        assertInvalid(process.get("/test/open", "Authorization", bearer("not.a.token")));
        assertProblem(process.get("/test/open", "Authorization",
                bearer(process.tokens().accessAt(Instant.now().minus(1, ChronoUnit.HOURS)))),
                401, "token-expired");
        assertThat(process.get("/test/open", "Authorization", bearer(process.tokens().access()))
                .statusCode()).as("a valid one on the same route is welcome").isEqualTo(200);
        assertThat(process.calls().openInvocations()).isEqualTo(openBefore + 1);
    }

    // --- A valid token ---

    @Test
    void aValidTokenAuthenticatesAndThePrincipalEqualsTheClaims() {
        HttpResponse<String> response = process.get("/test/whoami", "Authorization",
                bearer(process.tokens().access()));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = HarnessProcess.json(response);
        assertThat(body.get("accountId").asString()).isEqualTo(HarnessTokens.ACCOUNT.toString());
        assertThat(body.get("institutionId").asString())
                .isEqualTo(HarnessTokens.INSTITUTION.toString());
        assertThat(body.get("sessionId").asString()).isEqualTo(HarnessTokens.SESSION.toString());
        assertThat(body.get("springAuthenticated").asBoolean()).isTrue();
    }

    @Test
    void aValidTokenOutsideTheAuthenticatedListGets403AndAnotherMethodToo() {
        String token = process.tokens().access();

        assertProblem(process.get("/x", "Authorization", bearer(token)), 403, "forbidden");
        assertProblem(process.send("POST", "/test/whoami", "Authorization", bearer(token)), 403,
                "forbidden");
        assertProblem(process.get("/does/not/exist", "Authorization", bearer(token)), 403,
                "forbidden");
        assertThat(process.calls().protectedInvocations()).isZero();
        assertThat(process.calls().authenticatedInvocations()).isEqualTo(authenticatedBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/TEST/WHOAMI", "/test/whoami;a=b", "/test/whoami/.", "/test//whoami",
            "/y/../test/whoami"})
    void aVariantOfAnAuthenticatedPathIsNeverServed(String path) {
        HttpResponse<String> response = process.get(path, "Authorization",
                bearer(process.tokens().access()));

        assertThat(response.statusCode()).as("%s", path).isIn(400, 403);
        assertThat(process.calls().authenticatedInvocations()).isEqualTo(authenticatedBefore);
    }

    @Test
    void theLiveSessionCheckIsNotAnsweredYetSoItDeniesInsteadOfTrusting() {
        HttpResponse<String> response = process.get("/test/live", "Authorization",
                bearer(process.tokens().access()));

        assertInvalid(response);
        assertThat(process.calls().authenticatedInvocations()).isEqualTo(authenticatedBefore);
    }
}
