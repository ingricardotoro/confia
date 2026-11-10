package com.confia.shared.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.confia.kernel.Permission;
import com.confia.shared.security.token.MfaPurpose;
import com.confia.shared.web.authorization.PermissionedEndpoint;
import com.confia.shared.web.authorization.PermissionedEndpoints;
import com.confia.shared.web.harness.HarnessProcess;
import com.confia.shared.web.harness.RecordingPermissionResolver;
import com.confia.shared.web.harness.RecordingSessionValidity;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Specs/authorization and specs/web-edge of rbac-permission-matrix-and-audit-integration, the decision
 * of the administrative chain over the closed list of permission routes (design.md D6, D7, D12): the
 * real chain, the real filter and the real decider behind the database-free harness, over HTTP, with
 * doubles for the resolver and the metrics. The harness list holds two routes, one with a path
 * variable, and the controller behind them counts its invocations and answers every method.
 */
class PermissionEdgeTest {

    private static final String ROUTE = "/test/permissioned";
    private static final String SENTINEL = "SENTINEL-RESOLVER-6";
    private static final UUID READER = UUID.fromString("5d1b1f6e-7c8f-4d3a-9b0e-1a2b3c4d5e61");
    private static final UUID CASHIER = UUID.fromString("5d1b1f6e-7c8f-4d3a-9b0e-1a2b3c4d5e62");

    private static HarnessProcess process;

    private int controllerBefore;
    private int metricsBefore;

    @BeforeAll
    static void start() {
        process = HarnessProcess.start();
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    @BeforeEach
    void freshDoubles() {
        process.bean(RecordingSessionValidity.class).answer(true);
        RecordingPermissionResolver resolver = process.permissionResolver();
        resolver.recover();
        resolver.grant(READER, Permission.IDENTITY_READ);
        resolver.grant(CASHIER, Permission.PAYMENTS_CREATE);
        controllerBefore = process.calls().permissionedInvocations();
        metricsBefore = process.authorizationMetrics().resolutionFailedCount();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private int controllerInvocations() {
        return process.calls().permissionedInvocations() - controllerBefore;
    }

    private HttpResponse<String> getAs(String path, String token) {
        return process.get(path, "Authorization", bearer(token));
    }

    // --- AZ25 to AZ28: what the decision demands of the session ---

    @Test
    void aPasswordOnlyTokenIsForbiddenAndNeitherTheControllerNorTheResolverIsAsked() {
        int resolverBefore = process.permissionResolver().invocations();

        HttpResponse<String> response = getAs(ROUTE, process.tokens().passwordOnly(READER));

        assertProblem(response, 403, "forbidden");
        assertThat(controllerInvocations()).isZero();
        assertThat(process.permissionResolver().invocations()).isEqualTo(resolverBefore);
    }

    @Test
    void aFullSessionTokenWithThePermissionReachesTheController() {
        HttpResponse<String> response = getAs(ROUTE, process.tokens().fullSession(READER));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("permissioned");
        assertThat(controllerInvocations()).isEqualTo(1);
    }

    @Test
    void aFullSessionTokenWithoutThePermissionIsForbidden() {
        HttpResponse<String> response = getAs(ROUTE, process.tokens().fullSession(CASHIER));

        assertProblem(response, 403, "forbidden");
        assertThat(controllerInvocations()).isZero();
    }

    @Test
    void noCredentialIsUnauthorizedAndNothingIsAsked() {
        int resolverBefore = process.permissionResolver().invocations();

        HttpResponse<String> response = process.get(ROUTE);

        assertProblem(response, 401, "authentication-required");
        assertThat(controllerInvocations()).isZero();
        assertThat(process.permissionResolver().invocations()).isEqualTo(resolverBefore);
    }

    @ParameterizedTest
    @EnumSource(MfaPurpose.class)
    void aRestrictedTokenIsInvalidOnAPermissionRoute(MfaPurpose purpose) {
        int resolverBefore = process.permissionResolver().invocations();

        HttpResponse<String> response = getAs(ROUTE, process.tokens().restricted(purpose));

        assertProblem(response, 401, "token-invalid");
        assertThat(controllerInvocations()).isZero();
        assertThat(process.permissionResolver().invocations()).isEqualTo(resolverBefore);
    }

    @Test
    void aTokenOfARevokedSessionIsRejectedBeforeTheResolver() {
        process.bean(RecordingSessionValidity.class).answer(false);
        int resolverBefore = process.permissionResolver().invocations();

        HttpResponse<String> response = getAs(ROUTE, process.tokens().fullSession(READER));

        assertProblem(response, 401, "token-invalid");
        assertThat(controllerInvocations()).isZero();
        assertThat(process.permissionResolver().invocations()).isEqualTo(resolverBefore);
    }

    /**
     * The drift test of design.md D6: every entry of the list the chain was built from is walked, not
     * one example, so an entry the filter forgot to derive its live-session check from is named.
     */
    @Test
    void everyPermissionedEntryRejectsARevokedSession() {
        List<PermissionedEndpoint> entries = process.bean(PermissionedEndpoints.class).endpoints();
        assertThat(entries).as("the walk is non-vacuous: two entries, one with a path variable")
                .hasSizeGreaterThanOrEqualTo(2)
                .anyMatch(entry -> entry.pattern().contains("{"));
        process.bean(RecordingSessionValidity.class).answer(false);
        int resolverBefore = process.permissionResolver().invocations();

        for (PermissionedEndpoint entry : entries) {
            HttpResponse<String> response = process.send(entry.method().name(),
                    entry.pattern().replace("{id}", "123"), "Authorization",
                    bearer(process.tokens().fullSession(READER)));

            assertThat(response.statusCode()).as("revoked session on %s %s", entry.method(),
                    entry.pattern()).isEqualTo(401);
            assertThat(response.body()).contains("token-invalid");
        }
        assertThat(process.permissionResolver().invocations()).isEqualTo(resolverBefore);
    }

    // --- AZ24 and AZ31 to AZ33, AZ37: what the client can and cannot influence ---

    @Test
    void aPermissionsClaimInTheTokenIsRejected() {
        int resolverBefore = process.permissionResolver().invocations();

        HttpResponse<String> response = getAs(ROUTE, process.tokens().withPermissions());

        assertProblem(response, 401, "token-invalid");
        assertThat(controllerInvocations()).isZero();
        assertThat(process.permissionResolver().invocations()).isEqualTo(resolverBefore);
    }

    @Test
    void aPermissionTheClientClaimsInAHeaderAParameterOrTheBodyIsIgnored() {
        String token = bearer(process.tokens().fullSession(CASHIER));

        HttpResponse<String> byHeader = process.get(ROUTE, "Authorization", token,
                "X-Permission", "identity:read");
        HttpResponse<String> byParameter = process.get(ROUTE + "?permission=identity:read",
                "Authorization", token);
        HttpResponse<String> byBody = process.sendWithBody("GET", ROUTE,
                "{\"claimedPermission\":\"identity:read\"}", "Authorization", token,
                "Content-Type", "application/json");

        assertProblem(byHeader, 403, "forbidden");
        assertProblem(byParameter, 403, "forbidden");
        assertProblem(byBody, 403, "forbidden");
        assertThat(controllerInvocations()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test/permissioned/", "/TEST/PERMISSIONED", "/test/permissioned;a=b",
            "/test/./permissioned", "/test/other/../permissioned"})
    void aVariantOfTheRouteNeitherEludesTheDecisionNorReachesTheController(String variant) {
        HttpResponse<String> forTheCashier = getAs(variant, process.tokens().fullSession(CASHIER));
        HttpResponse<String> forTheReader = getAs(variant, process.tokens().fullSession(READER));

        assertThat(forTheCashier.statusCode() / 100).isNotEqualTo(2);
        assertThat(forTheReader.statusCode() / 100).as("even for the one who holds the permission")
                .isNotEqualTo(2);
        assertThat(controllerInvocations()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void anotherMethodOnAPermissionRouteFallsToTheFinalDenialWithoutAskingTheResolver(
            String method) {
        int resolverBefore = process.permissionResolver().invocations();

        HttpResponse<String> response = process.send(method, ROUTE, "Authorization",
                bearer(process.tokens().fullSession(READER)), "Origin",
                HarnessProcess.ALLOWED_ORIGIN);

        assertProblem(response, 403, "forbidden");
        assertThat(controllerInvocations()).isZero();
        assertThat(process.permissionResolver().invocations()).isEqualTo(resolverBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "abc"})
    void aTemplateWithAPathVariableMatchesItsRequestsAndTheResolverIsAskedOncePerRequest(
            String id) {
        int resolverBefore = process.permissionResolver().invocations();

        HttpResponse<String> response = getAs("/test/accounts/" + id,
                process.tokens().fullSession(CASHIER));

        assertProblem(response, 403, "forbidden");
        assertThat(process.permissionResolver().invocations())
                .as("the entry matched, so the decision asked the resolver; the final denial would not")
                .isEqualTo(resolverBefore + 1);
        assertThat(controllerInvocations()).isZero();
    }

    // --- Task 4: what the handler hands to the recorder ---

    @ParameterizedTest
    @ValueSource(strings = {"123", "abc"})
    void aDenialByPermissionIsRecordedOncePerRequestWithTheTemplateAndNeverTheValue(String id) {
        process.denialRecorder().clear();

        HttpResponse<String> response = getAs("/test/accounts/" + id,
                process.tokens().fullSession(CASHIER));

        tools.jackson.databind.JsonNode body = assertProblem(response, 403, "forbidden");
        assertThat(process.denialRecorder().calls()).hasSize(1);
        var call = process.denialRecorder().calls().get(0);
        assertThat(call.denied().permission()).isEqualTo(Permission.IDENTITY_READ);
        assertThat(call.denied().routeTemplate()).isEqualTo("/test/accounts/{id}");
        assertThat(call.denied().actor().accountId()).isEqualTo(CASHIER);
        assertThat(call.requestId()).as("the trace id of the problem the client received")
                .isEqualTo(body.get("traceId").asString());
        assertThat(call.denied().routeTemplate() + call.requestId()).doesNotContain(id);
    }

    @Test
    void theCasesThatAreNotADenialByPermissionAreNeverRecorded() {
        process.denialRecorder().clear();

        assertProblem(getAs(ROUTE, process.tokens().passwordOnly(READER)), 403, "forbidden");
        assertProblem(getAs("/test/not-mapped-anywhere", process.tokens().fullSession(READER)), 403,
                "forbidden");
        assertProblem(process.get(ROUTE), 401, "authentication-required");
        assertThat(getAs(ROUTE, process.tokens().fullSession(READER)).statusCode()).isEqualTo(200);

        assertThat(process.denialRecorder().calls()).isEmpty();
    }

    // --- AZ60 to AZ64, WE101, WE102: a resolution that fails ---

    @Test
    void aFailingResolverAnswersInternalErrorAndNeverForbidden() {
        process.permissionResolver().fail(new IllegalStateException(SENTINEL));

        HttpResponse<String> response = getAs(ROUTE, process.tokens().fullSession(READER));

        assertProblem(response, 500, "internal-error");
        assertThat(response.statusCode()).isNotEqualTo(403);
        assertThat(response.body()).doesNotContain("forbidden");
        assertThat(controllerInvocations()).isZero();
        assertThat(process.authorizationMetrics().resolutionFailedCount() - metricsBefore)
                .isEqualTo(1);
    }

    @Test
    void theFailureOfAResolverAnswersTheSameWhateverTheCauseWas() {
        process.permissionResolver().fail(new org.springframework.dao.DataAccessResourceFailureException(
                SENTINEL, new SQLException(SENTINEL, "08006")));

        HttpResponse<String> response = getAs(ROUTE, process.tokens().fullSession(READER));

        assertProblem(response, 500, "internal-error");
        assertThat(process.authorizationMetrics().resolutionFailedCount() - metricsBefore)
                .isEqualTo(1);
    }

    @Test
    void theFailureLeaksNeitherTheMessageNorTheClassInTheResponseOrTheLog() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        root.setLevel(Level.DEBUG);
        process.permissionResolver().fail(new InternalResolverFailure(SENTINEL));
        HttpResponse<String> response;
        try {
            response = getAs(ROUTE, process.tokens().fullSession(READER));
        } finally {
            root.detachAppender(logged);
            root.setLevel(previous);
        }

        assertProblem(response, 500, "internal-error");
        assertThat(response.body() + " " + response.headers().map())
                .doesNotContain(SENTINEL).doesNotContain("InternalResolverFailure");
        assertThat(logged.list).as("non-vacuous: the failure was logged")
                .anyMatch(event -> event.getLevel() == Level.ERROR);
        for (ILoggingEvent event : logged.list) {
            assertThat(event.getFormattedMessage() + " " + event.getKeyValuePairs() + " "
                    + (event.getThrowableProxy() == null ? ""
                    : ThrowableProxyUtil.asString(event.getThrowableProxy()))).doesNotContain(SENTINEL);
        }
    }

    @Test
    void afterTheResolverRecoversTheSameActorReachesTheController() {
        process.permissionResolver().fail(new IllegalStateException(SENTINEL));
        assertProblem(getAs(ROUTE, process.tokens().fullSession(READER)), 500, "internal-error");

        process.permissionResolver().recover();
        HttpResponse<String> response = getAs(ROUTE, process.tokens().fullSession(READER));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(controllerInvocations()).isEqualTo(1);
    }

    /** The class whose name a response must never repeat. */
    private static final class InternalResolverFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InternalResolverFailure(String message) {
            super(message);
        }
    }
}
