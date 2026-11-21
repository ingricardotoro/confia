package com.confia.identity.application;

import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.SessionLifetimePolicy;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.Permission;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the session an access token belongs to (session-tokens-and-web-layer design.md decision 13):
 * the one use case behind {@code GET /api/v1/auth/sessions/current}. The family is read in a
 * read-only {@code READ COMMITTED} transaction whose security context is the actor's, so the
 * row-level security of the administrative role keeps a family of another institution out. It takes
 * no row lock, writes nothing, audits nothing and never moves {@code last_used_at}: inactivity is
 * measured when the session is refreshed, not when it is read.
 *
 * <p>The authentication filter has already checked, on this route, that the session is live. The
 * answer is checked again against the same rule ({@link SessionLifetimePolicy#isLive}) because the
 * session may have ended between that check and this read; then there is nothing to describe, and the
 * caller treats it like any other rejected credential. A failure of the store is not turned into an
 * answer: it propagates and the request ends in {@code 500}.
 */
public final class DescribeCurrentSession {

    private final TransactionRunner runner;
    private final RefreshTokenFamilyRepository families;
    private final StaffRoleAssignmentRepository assignments;
    private final Clock clock;
    private final SessionLifetimePolicy policy = new SessionLifetimePolicy();

    public DescribeCurrentSession(TransactionRunner runner, RefreshTokenFamilyRepository families,
            StaffRoleAssignmentRepository assignments, Clock clock) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.families = Objects.requireNonNull(families, "families");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The session of {@code actor}, or empty when it is no longer a live session of that account. */
    public Optional<CurrentSessionView> execute(AuthenticatedActor actor) {
        Objects.requireNonNull(actor, "actor");
        String requestId = RequestOrigin.current().map(RequestOrigin::requestId)
                .orElseGet(UUID::randomUUID).toString();
        InstitutionId institution = new InstitutionId(actor.institutionId());
        StaffAccountId owner = new StaffAccountId(actor.accountId());
        return runner.executeReadOnly(actor.securityContext(requestId), () -> families
                .readById(institution, actor.sessionId())
                .filter(family -> policy.isLive(family, owner, clock.instant()))
                .map(family -> viewOf(actor, family, institution, owner)));
    }

    private CurrentSessionView viewOf(AuthenticatedActor actor, RefreshTokenFamilyState family,
            InstitutionId institution, StaffAccountId owner) {
        return new CurrentSessionView(actor.sessionId(), actor.accountId(), actor.institutionId(),
                methodsOf(actor), actor.expiresAt(), family.absoluteExpiresAt(),
                policy.idleExpiresAt(family.lastUsedAt()), permissionsOf(actor, institution, owner));
    }

    /**
     * The permission codes the server would grant this session, read inside the transaction of the
     * session read with the same translation the resolver uses ({@link EffectiveRolePermissions}). It
     * does not call the resolver port, which only the permission decider may ask. Like the decider, a
     * session that has not proved the second factor holds nothing, and its role is not read. No role, or
     * a code outside the catalog, is an empty list.
     */
    private List<String> permissionsOf(AuthenticatedActor actor, InstitutionId institution,
            StaffAccountId owner) {
        if (!actor.methods().contains(AuthenticationMethod.ONE_TIME_PASSWORD)) {
            return List.of();
        }
        return EffectiveRolePermissions.of(assignments, institution, owner).stream()
                .map(Permission::code).sorted().toList();
    }

    /** The {@code amr} values in the order the token writes them: the declaration order of the enum. */
    private static List<String> methodsOf(AuthenticatedActor actor) {
        return Arrays.stream(AuthenticationMethod.values())
                .filter(actor.methods()::contains)
                .map(AuthenticationMethod::claimValue)
                .toList();
    }
}
