package com.confia.identity.application;

import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.RefreshTokenState;
import com.confia.identity.domain.SessionLifetimePolicy;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ends the session a refresh token belongs to (session-endpoints-cookie-and-csrf design.md decision
 * 15; scenarios I194 to I197 and I198). It has no controller yet: it is the use case behind
 * {@code DELETE /api/v1/auth/sessions/current}, and the controller decides the answer, which is the
 * same for every outcome.
 *
 * <p>The institution comes from {@link LoginInstitutionProvider}, as in {@link RotateRefreshToken}:
 * the request carries a cookie and no institution. Everything that is stored happens in <b>one</b>
 * transaction opened by {@link TransactionRunner}, in this order:
 *
 * <ol>
 *   <li>a text that cannot be an issued token, or no text at all, is nothing to do and opens no
 *       transaction;</li>
 *   <li>the token is looked up by its hash <b>without a lock</b>, and a token that was already
 *       consumed is found like any other, so the token of a rotation that already happened closes the
 *       family too; none, or one of another institution (row-level security keeps it out), is nothing
 *       to do;</li>
 *   <li>the row of its family is locked, the same point of serialization the rotation uses, and only
 *       that: the account is never locked here, so the order account, then families, of the other
 *       revocations cannot meet this one in a cycle;</li>
 *   <li>a family that is live at the instant read after the lock is revoked with {@link
 *       SessionRevocationReason#LOGOUT}, conditionally on it not being revoked yet, and its encrypted
 *       successors are emptied;</li>
 *   <li>one entry is written in the same transaction.</li>
 * </ol>
 *
 * <p><b>Nothing to do means nothing happens and nothing is written</b>: not an entry, not a changed
 * reason, not a changed instant. A family that is already revoked (by reuse, by a password change, by
 * an earlier logout), that has ended its absolute life or that is idle is not live, and an attempt to
 * close it again must not rewrite what it says or leave a trace that a session was closed. The
 * answer to the client must not tell these cases apart, which the use case helps by returning
 * nothing at all. A closing that races a rotation of the same token is serialized by the lock of the
 * family: whichever comes second finds the outcome of the first, and the family ends revoked with no
 * token that can be presented.
 */
public final class EndCurrentSession {

    private static final String SYSTEM_ACTOR_KIND = "system";

    private final TransactionRunner transactionRunner;
    private final LoginInstitutionProvider institutionProvider;
    private final RefreshTokenFamilyRepository families;
    private final RefreshTokenRepository tokens;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final SessionLifetimePolicy lifetimes = new SessionLifetimePolicy();

    public EndCurrentSession(TransactionRunner transactionRunner,
            LoginInstitutionProvider institutionProvider, RefreshTokenFamilyRepository families,
            RefreshTokenRepository tokens, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.institutionProvider = Objects.requireNonNull(institutionProvider, "institutionProvider");
        this.families = Objects.requireNonNull(families, "families");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param presentedRefreshToken the text of the refresh token the client holds, or {@code null}
     *     when it presented none
     * @param requestId the identifier of the request, for the entry; a blank one gets a fresh
     *     identifier
     */
    public void execute(String presentedRefreshToken, String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        Optional<RefreshTokenHash> hash = hashOf(presentedRefreshToken);
        if (hash.isEmpty()) {
            return;
        }
        InstitutionId institutionId = institutionProvider.loginInstitutionId();
        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
        SecurityContext context = new SecurityContext("", SYSTEM_ACTOR_KIND,
                institutionId.value().toString(), auditRequestId.toString());

        transactionRunner.execute(context, () -> {
            endWithinTransaction(institutionId, auditRequestId, hash.get());
            return null;
        });
    }

    private void endWithinTransaction(InstitutionId institutionId, UUID auditRequestId,
            RefreshTokenHash hash) {
        // 2. Without a lock: a made-up text costs a SHA-256 and one indexed read.
        Optional<RefreshTokenState> found = tokens.findByHash(institutionId, hash);
        if (found.isEmpty()) {
            return;
        }

        // 3. The lock of the family, and its state as that statement reads it.
        UUID familyId = found.get().familyId();
        RefreshTokenFamilyState family = families.lockById(institutionId, familyId).orElseThrow(
                () -> new IllegalStateException("a refresh token whose family does not exist"));

        // 4. The instant is read after the lock, so it belongs to the moment the closing really runs.
        Instant now = clock.instant();
        if (!lifetimes.isLive(family, family.accountId(), now)) {
            return;
        }
        if (!families.revoke(institutionId, familyId, SessionRevocationReason.LOGOUT, now)) {
            throw new IllegalStateException("a family that is live under its own lock could not "
                    + "be revoked");
        }
        tokens.clearSuccessors(institutionId, familyId);

        // 5. In the same transaction.
        auditLogWriter.append(SessionRevocationAudit.logout(institutionId, family.accountId(),
                auditRequestId, 1));
    }

    /** A malformed text, or none, is simply not found: it cannot be the text of any issued token. */
    private static Optional<RefreshTokenHash> hashOf(String presentedToken) {
        if (presentedToken == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(RefreshTokenHash.of(PlainRefreshToken.of(presentedToken)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}
