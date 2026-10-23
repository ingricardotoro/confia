package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.RefreshOutcome.RotationKind;
import com.confia.identity.application.RefreshSession.RefreshResult;
import com.confia.identity.application.RefreshSession.Refreshed;
import com.confia.identity.application.RefreshSession.Rejected;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.ClientAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The refresh as the web layer sees it (session-endpoints-cookie-and-csrf design.md decision 14),
 * over the real rotation and a real PostgreSQL: a rotation, fresh or replayed in the grace window,
 * comes back as a grant with the two ends of the session, and every rejection comes back as the same
 * value, so the caller cannot tell a stolen token from an unknown one.
 */
class RefreshSessionIT extends RotationIntegrationTest {

    private static final String NINE = "2026-10-12T09:00:00Z";

    @Test
    void aFreshRotationIsAGrantWithTheEndsOfTheSessionAndTheNewRefreshToken() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, true);
        IssuedSession first = issueSession(NINE).execute(context(),
                commandFrom(ana, true, AGENT_ONE, "203.0.113.9"));

        RefreshResult result = refresh("2026-10-12T09:15:00Z", first.refreshToken().value());

        assertThat(result).isInstanceOf(Refreshed.class);
        Refreshed refreshed = (Refreshed) result;
        assertThat(refreshed.kind()).isEqualTo(RotationKind.FRESH);
        SessionGrant grant = refreshed.session();
        assertThat(grant.refreshToken()).isNotEqualTo(first.refreshToken().value());
        AuthenticatedActor actor = verifierAt("2026-10-12T09:15:00Z")
                .verifyAccess(grant.accessToken());
        assertThat(actor.sessionId()).isEqualTo(first.sessionId());
        assertThat(grant.accessTokenExpiresAt()).isEqualTo(actor.expiresAt());
        assertThat(grant.sessionExpiresAt())
                .isEqualTo(instantOf(familyRow(first.sessionId()), "absolute_expires_at"));
        assertThat(grant.idleExpiresAt())
                .isEqualTo(Instant.parse("2026-10-12T09:15:00Z").plus(30, ChronoUnit.MINUTES));
    }

    @Test
    void aRetryInsideTheGraceWindowIsAGrantWithTheSameRefreshToken() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, null));
        Refreshed fresh = (Refreshed) refresh("2026-10-12T09:15:00Z", first.refreshToken().value());

        RefreshResult retry = refresh("2026-10-12T09:15:04Z", first.refreshToken().value());

        assertThat(retry).isInstanceOf(Refreshed.class);
        Refreshed replayed = (Refreshed) retry;
        assertThat(replayed.kind()).isEqualTo(RotationKind.GRACE);
        assertThat(replayed.session().refreshToken()).isEqualTo(fresh.session().refreshToken());
        assertThat(replayed.session().idleExpiresAt())
                .isEqualTo(Instant.parse("2026-10-12T09:15:04Z").plus(30, ChronoUnit.MINUTES));
    }

    @Test
    void everyRejectionIsTheSameValueWhateverTheReason() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession live = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, "203.0.113.9"));
        IssuedSession reused = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, "203.0.113.9"));
        refresh("2026-10-12T09:15:00Z", reused.refreshToken().value());
        IssuedSession bound = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, "203.0.113.9"));
        UUID revokedFamily = live.sessionId();
        transactionRunner().execute(context(), () -> new JooqRefreshTokenFamilyRepository(dsl)
                .revoke(institutionId, revokedFamily, SessionRevocationReason.LOGOUT,
                        Instant.parse("2026-10-12T09:05:00Z")));

        RefreshResult unknown = refresh("2026-10-12T09:20:00Z",
                "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE");
        RefreshResult malformed = refresh("2026-10-12T09:20:00Z", "not a token");
        RefreshResult revoked = refresh("2026-10-12T09:20:00Z", live.refreshToken().value());
        RefreshResult reuse = refresh("2026-10-12T09:20:00Z", reused.refreshToken().value());
        RefreshResult binding = refreshFrom("2026-10-12T09:20:00Z", bound.refreshToken().value(),
                AGENT_TWO, "198.51.100.7");

        assertThat(List.of(unknown, malformed, revoked, reuse, binding))
                .containsOnly(new Rejected());
        assertThat(familyRow(reused.sessionId()).get("revoked_reason", String.class))
                .as("the reason stays in the trail").isEqualTo("reuse_detected");
        assertThat(familyRow(bound.sessionId()).get("revoked_reason", String.class))
                .isEqualTo("binding_changed");
    }

    private RefreshResult refresh(String instant, String token) {
        return refreshFrom(instant, token, AGENT_ONE, null);
    }

    private RefreshResult refreshFrom(String instant, String token, String agent, String address) {
        RotateRefreshToken rotation = rotation(fixedClock(instant), new JooqAuditLogWriter(dsl),
                institutionId);
        return new RefreshSession(rotation).execute(new RotateRefreshTokenCommand(token, agent,
                address == null ? null : ClientAddress.parseLiteral(address), ""));
    }
}
