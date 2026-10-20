package com.confia.identity.application;

import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.SessionLifetimePolicy;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.security.SessionValidity;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether the session behind an access token is still valid (session-tokens-and-web-layer design.md
 * decision 7): the one adapter of {@link SessionValidity}, which the authentication filter asks for
 * the routes that declare a live-session check.
 *
 * <p>The answer is "active" only when the family exists for the actor's institution, belongs to the
 * actor's account, is not revoked, has not reached its absolute end and has been used within the
 * last 30 minutes ({@link SessionLifetimePolicy#isLive}). The read runs in a read-only {@code READ
 * COMMITTED} transaction whose security context is the actor's, so the row-level security of the
 * administrative role is what keeps a family of another institution out. It takes no row lock, writes
 * nothing, audits nothing and never moves {@code last_used_at}: inactivity is measured when the
 * session is refreshed, not when it is read.
 *
 * <p>A failure of the store is not turned into an answer: it propagates, the request ends in
 * {@code 500}, and a broken database never reads as an active session.
 */
public final class SessionLiveness implements SessionValidity {

    private final TransactionRunner runner;
    private final RefreshTokenFamilyRepository families;
    private final Clock clock;
    private final SessionLifetimePolicy policy = new SessionLifetimePolicy();

    public SessionLiveness(TransactionRunner runner, RefreshTokenFamilyRepository families,
            Clock clock) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.families = Objects.requireNonNull(families, "families");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean isActive(AuthenticatedActor actor) {
        Objects.requireNonNull(actor, "actor");
        String requestId = RequestOrigin.current().map(RequestOrigin::requestId)
                .orElseGet(UUID::randomUUID).toString();
        InstitutionId institution = new InstitutionId(actor.institutionId());
        StaffAccountId owner = new StaffAccountId(actor.accountId());
        return runner.executeReadOnly(actor.securityContext(requestId), () -> {
            Optional<RefreshTokenFamilyState> family =
                    families.readById(institution, actor.sessionId());
            return family.isPresent() && policy.isLive(family.get(), owner, clock.instant());
        });
    }
}
