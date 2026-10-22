package com.confia.identity.application;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.application.RefreshOutcome.Rejected;
import com.confia.identity.application.RefreshOutcome.Rotated;
import com.confia.identity.application.ResetOutcome.Completed;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import com.confia.support.SharedPostgresContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Follow-up F1a of the archived session-tokens change (session-endpoints-cookie-and-csrf specs/identity
 * spec.md, scenario I207), asserted directly and not through an indirect effect: after a password
 * reset, no token of the account is valid. Every refresh token of every family of the account,
 * presented to the real rotation, is {@code Rejected} with {@code family_revoked}, and every access
 * token of those families, presented to a route that checks the session on the real administrative
 * process, is {@code 401 token-invalid}. An account that did not reset keeps both.
 *
 * <p>Tokens are made at the real instant, with a key pair generated for the run that the process is
 * started with, because the process reads the real clock. The reset goes through the real use case
 * and the real adapters. The control runs the same flow with a revoker that revokes nothing and shows
 * that the same checks then find every token alive, so the assertions of the main test are not
 * vacuous.
 */
class PasswordResetLeavesNoValidTokenIT extends RotationIntegrationTest {

    private static final String ROUTE = "/api/v1/auth/sessions/current";
    private static final String CARLOS = "carlos.ramirez@colegio.edu.hn";
    private static final String NEW_PASSWORD = "Lempira y maíz 2026";
    private static final SecondFactorProof NONE = new SecondFactorProof.None();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

    @BeforeAll
    static void startTheProcess() throws NoSuchAlgorithmException {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jws = new CompactJws(SigningKeyRing.of(SigningKey.signing(
                AdminProcessLauncher.signingKid(), keys.getPublic(), keys.getPrivate())));
        process = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys);
        port = ((WebServerApplicationContext) process).getWebServer().getPort();
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    @Test
    void afterTheResetNoRefreshTokenRotatesAndNoAccessTokenOfTheAccountIsAccepted() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        StaffAccountId carlos = seedStaffAccount(CARLOS, CURRENT_PASSWORD, false);
        List<IssuedSession> anas = threeSessionsOf(ana);
        IssuedSession carlosSession = issueNow(carlos);
        assertThat(anas).allSatisfy(session -> assertThat(current(session).statusCode())
                .as("before the reset every access token of the account is accepted")
                .isEqualTo(200));
        PlainPasswordResetToken token = issueTokenAt(ana, Instant.now().toString());

        ResetPasswordDecision decision = resetAt(token.value(), NEW_PASSWORD, NONE,
                Instant.now().toString());

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        for (IssuedSession session : anas) {
            assertThat(rotateNow(session)).isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
            assertProblem(current(session), 401, "token-invalid");
        }
        assertThat(rotateNow(carlosSession)).as("the other account still rotates")
                .isInstanceOf(Rotated.class);
        assertThat(current(carlosSession).statusCode()).as("and is still accepted").isEqualTo(200);
    }

    @Test
    void controlWithoutTheRevokerEveryTokenOfTheAccountIsStillAlive() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        List<IssuedSession> anas = threeSessionsOf(ana);
        PlainPasswordResetToken token = issueTokenAt(ana, Instant.now().toString());
        AccountSessionRevoker revokesNothing = (institution, account, reason, now) -> 0;

        ResetPasswordDecision decision = resetUseCase(Instant.now().toString(),
                new JooqAuditLogWriter(dsl), new JooqPasswordResetTokenRepository(dsl),
                institutionId, revokesNothing)
                .execute(context(), new ResetPasswordCommand(token.value(), NEW_PASSWORD, NONE));

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        assertThat(anas).allSatisfy(session ->
                assertThat(current(session).statusCode()).isEqualTo(200));
        assertThat(anas).allSatisfy(session ->
                assertThat(rotateNow(session)).isInstanceOf(Rotated.class));
    }

    private List<IssuedSession> threeSessionsOf(StaffAccountId account) {
        List<IssuedSession> sessions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sessions.add(issueNow(account));
        }
        return sessions;
    }

    /** A real issuance at the real instant, signed with the key the process verifies. */
    private IssuedSession issueNow(StaffAccountId account) {
        Clock clock = Clock.systemUTC();
        return new IssueSession(transactionRunner(), new JooqStaffAccountRepository(dsl),
                new JooqRefreshTokenFamilyRepository(dsl), new JooqRefreshTokenRepository(dsl),
                new AccessTokenIssuer(jws, clock), new JooqAuditLogWriter(dsl), clock,
                new SecureRandom())
                .execute(context(), new IssueSessionCommand(account, false, AGENT_ONE, null));
    }

    private RefreshOutcome rotateNow(IssuedSession session) {
        return rotation(Clock.systemUTC(), new JooqAuditLogWriter(dsl), institutionId)
                .execute(context(), new RotateRefreshTokenCommand(
                        session.refreshToken().value(), AGENT_ONE));
    }

    private static HttpResponse<String> current(IssuedSession session) {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + ROUTE))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + session.accessToken().compact())
                .GET().build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
