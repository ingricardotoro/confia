package com.confia.identity.application;

import com.confia.identity.application.RefreshOutcome.RotationKind;
import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.RefreshTokenRow;
import com.confia.identity.domain.RefreshTokenState;
import com.confia.identity.domain.RotationDecision;
import com.confia.identity.domain.SessionLifetimePolicy;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.security.token.AccessToken;
import com.confia.shared.security.token.AccessTokenIssuer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Rotates a refresh token (session-tokens-and-web-layer design.md decision 10; scenarios I91 to I94,
 * I109 to I114, I121, I142 and I145). The institution comes from {@link LoginInstitutionProvider},
 * as in {@code ResetPasswordWithToken}: a refresh request carries no access token and the cookie
 * carries no institution (risk R-6). Everything that is stored happens in <b>one</b> transaction
 * opened by {@link TransactionRunner}, in this order:
 *
 * <ol>
 *   <li>a text that cannot be an issued token is {@code UNKNOWN_TOKEN};</li>
 *   <li>the token is looked up by its hash <b>without a lock</b>; none, or one of another
 *       institution, is {@code UNKNOWN_TOKEN};</li>
 *   <li>the row of its family is locked, which is the point of serialization of every rotation and
 *       revocation of that family, and the state of the family is the one the locking statement reads
 *       (see {@link RefreshTokenFamilyRepository#lockById});</li>
 *   <li>the token is read again with a new statement, which under READ COMMITTED sees what the
 *       previous holder of the lock committed;</li>
 *   <li>the encrypted successors of the family that were consumed ten seconds ago or more are emptied
 *       (decision 10, step 5: the next use of the family empties it);</li>
 *   <li>{@link RotationDecision#decide} decides, as a pure function;</li>
 *   <li>the decision is carried out and audited in the same transaction.</li>
 * </ol>
 *
 * <p><b>The account is never locked here</b> (review of PR 9, I-2). A password reset locks the
 * account and then the families; a rotation takes only the family, so the two cannot wait for each
 * other in a cycle. A rotation that was waiting for a family the reset revoked reads it as revoked
 * once it gets the lock and rejects. The instant of the rotation is read after the lock, so it
 * belongs to the moment the rotation actually runs and {@code last_used_at} never moves back.
 *
 * <p><b>Every rejection is a returned value.</b> The transaction commits, with the entry that says
 * why. The access token is signed after the commit, so a token for a rotation that did not commit is
 * never handed out. A confirmed reuse revokes the family, empties its encrypted successors and is
 * audited in that same transaction; it notifies nobody (the notice to the holder is change 14's).
 *
 * <p><b>The grace window</b> (decision 11, D-N1). A normal rotation stores the successor encrypted
 * with {@link ColumnEncryptionService}, bound to the row of the token it consumes (the additional
 * authenticated data names that row, so a ciphertext copied to another row does not authenticate). A
 * retry from the same device strictly inside ten seconds is decided {@code GRACE_REPLAY} by the pure
 * decision, and only then is the stored value decrypted: it returns the same refresh token and a new
 * access token, issues nothing and counts as a use of the family. Outside the window the value is never
 * decrypted; it stays in the row until the next contact empties it.
 */
public final class RotateRefreshToken {

    private static final String ACTION_REFRESHED = "identity.session.refreshed";
    private static final String ACTION_REJECTED = "identity.session.refresh_rejected";
    private static final String ACTION_REUSE_DETECTED = "identity.session.reuse_detected";
    private static final String FAMILY_ENTITY_TYPE = "identity.refresh_token_family";
    /** The table and column of the encrypted successor: the first two parts of its additional authenticated data. */
    private static final String SUCCESSOR_TABLE = "identity_refresh_token";
    private static final String SUCCESSOR_COLUMN = "successor_token_ciphertext";
    private static final String UNKNOWN_TOKEN_LABEL = "unknown-token";
    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";
    static final String AFTER_FRESH = AuditJson.object().text("rotation", "fresh").toJson();
    static final String AFTER_GRACE = AuditJson.object().text("rotation", "grace").toJson();
    static final String AFTER_FAMILY_REVOKED =
            AuditJson.object().bool("familyRevoked", true).toJson();
    private static final String OUTCOME_DENIED = "denied";

    private final TransactionRunner transactionRunner;
    private final LoginInstitutionProvider institutionProvider;
    private final RefreshTokenFamilyRepository families;
    private final RefreshTokenRepository tokens;
    private final ColumnEncryptionService encryption;
    private final AccessTokenIssuer accessTokens;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final SecureRandom random;
    private final SessionLifetimePolicy lifetimes;

    public RotateRefreshToken(TransactionRunner transactionRunner,
            LoginInstitutionProvider institutionProvider, RefreshTokenFamilyRepository families,
            RefreshTokenRepository tokens, ColumnEncryptionService encryption,
            AccessTokenIssuer accessTokens, AuditLogWriter auditLogWriter, Clock clock,
            SecureRandom random) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.institutionProvider = Objects.requireNonNull(institutionProvider, "institutionProvider");
        this.families = Objects.requireNonNull(families, "families");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.encryption = Objects.requireNonNull(encryption, "encryption");
        this.accessTokens = Objects.requireNonNull(accessTokens, "accessTokens");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.lifetimes = new SessionLifetimePolicy();
    }

    public RefreshOutcome execute(SecurityContext context, RotateRefreshTokenCommand command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");

        InstitutionId institutionId = new InstitutionId(UUID.fromString(context.institutionId()));
        if (!institutionId.equals(institutionProvider.loginInstitutionId())) {
            // A wiring defect or an attack, never a rejection (the same rule as the reset).
            throw new IllegalStateException("refresh institution mismatch: the security context "
                    + "declares a different institution than the configured login institution");
        }

        Attempt attempt = transactionRunner.execute(context,
                () -> runWithinTransaction(institutionId, context.requestId(), command));

        // After the commit: nothing is signed for a rotation that did not commit.
        return switch (attempt) {
            case Attempt.Rejected rejected -> new RefreshOutcome.Rejected(rejected.reason());
            case Attempt.Rotated rotated -> {
                AccessToken access = accessTokens.issueAccess(rotated.accountId().value(),
                        institutionId.value(), rotated.familyId(),
                        IssueSession.methodsOf(rotated.secondFactorVerified()));
                yield new RefreshOutcome.Rotated(new IssuedSession(rotated.familyId(),
                        rotated.refreshToken(), rotated.refreshExpiresAt(), access,
                        access.expiresAt()), rotated.kind());
            }
        };
    }

    private Attempt runWithinTransaction(InstitutionId institutionId, String requestId,
            RotateRefreshTokenCommand command) {
        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);

        // 1-2. Without a lock: a made-up text costs a SHA-256 and one indexed read.
        Optional<RefreshTokenState> found = hashOf(command.presentedToken())
                .flatMap(hash -> tokens.findByHash(institutionId, hash));
        if (found.isEmpty()) {
            auditLogWriter.append(new AuditEntry(institutionId.value(), null, ACTOR_KIND_STAFF,
                    UNKNOWN_TOKEN_LABEL, null, null, auditRequestId, null, ACTION_REJECTED,
                    FAMILY_ENTITY_TYPE, UNKNOWN_TOKEN_LABEL, OUTCOME_DENIED, null,
                    reasonJson(RefreshRejection.UNKNOWN_TOKEN), null, null));
            return new Attempt.Rejected(RefreshRejection.UNKNOWN_TOKEN);
        }

        // 3. The lock of the family, and its state as that statement reads it.
        UUID familyId = found.get().familyId();
        RefreshTokenFamilyState family = families.lockById(institutionId, familyId).orElseThrow(
                () -> new IllegalStateException("a refresh token whose family does not exist"));

        // 4. A new statement, after the lock.
        RefreshTokenState token = tokens.findById(institutionId, found.get().id()).orElseThrow(
                () -> new IllegalStateException("a refresh token that disappeared under its lock"));

        // 5. The instant is read after the lock. Whatever was consumed ten seconds ago or more can no
        // longer be replayed, so its encrypted successor is emptied before anything is decided.
        Instant now = clock.instant();
        tokens.clearSuccessorsConsumedAtOrBefore(institutionId, familyId,
                lifetimes.latestConsumptionOutsideGraceWindow(now));

        // 6. The decision is a pure function.
        UserAgentFingerprint presenter = UserAgentFingerprint.of(command.userAgent());
        RotationDecision decision = RotationDecision.decide(family, token, presenter, now, lifetimes);

        // 7. Carry it out and audit it, in the same transaction.
        return switch (decision) {
            case ROTATE -> rotate(institutionId, auditRequestId, family, token, presenter, now);
            case REJECT_FAMILY_EXPIRED -> {
                tokens.clearSuccessors(institutionId, familyId);
                yield rejection(institutionId, auditRequestId, family, decision);
            }
            case REJECT_FAMILY_REVOKED, REJECT_TOKEN_EXPIRED ->
                    rejection(institutionId, auditRequestId, family, decision);
            case REUSE_DETECTED -> revokeForReuse(institutionId, auditRequestId, family, now);
            case GRACE_REPLAY -> graceReplay(institutionId, auditRequestId, family, token, now);
        };
    }

    private Attempt rotate(InstitutionId institutionId, UUID auditRequestId,
            RefreshTokenFamilyState family, RefreshTokenState token, UserAgentFingerprint presenter,
            Instant now) {
        UUID successorId = UUID.randomUUID();
        PlainRefreshToken successor = PlainRefreshToken.generate(random);
        Instant expiresAt = lifetimes.refreshExpiresAt(now, family.absoluteExpiresAt());
        // Bound to the row that will hold it: the consumed token, whose id is the row of the column.
        String ciphertext = encryption.encryptForNewValue(SUCCESSOR_TABLE, SUCCESSOR_COLUMN,
                institutionId, token.id().toString(),
                successor.value().getBytes(StandardCharsets.UTF_8));

        // The conditional update first, then the insert: the successor's key is deferred.
        if (!tokens.consume(institutionId, token.id(), now, presenter, successorId, ciphertext)) {
            throw new IllegalStateException("a token that is open under the lock of its family "
                    + "could not be consumed");
        }
        tokens.insert(institutionId, new RefreshTokenRow(successorId, family.id(),
                RefreshTokenHash.of(successor), now, expiresAt));
        families.recordUse(institutionId, family.id(), now);

        auditLogWriter.append(entry(institutionId, auditRequestId, family, ACTION_REFRESHED,
                OUTCOME_SUCCESS, AFTER_FRESH));
        return new Attempt.Rotated(family.id(), family.accountId(), family.secondFactorVerified(),
                successor, expiresAt, RotationKind.FRESH);
    }

    /**
     * A retry strictly inside the window, from the device that presented the consumption, with the
     * family alive and not advanced (the decision guarantees all of it, and with it that the stored
     * value is present): the successor is decrypted, and nothing is issued. The family counts the retry
     * as a use. The access token is signed after the commit, as for a normal rotation, so it is a new
     * one with the claims of the family.
     */
    private Attempt graceReplay(InstitutionId institutionId, UUID auditRequestId,
            RefreshTokenFamilyState family, RefreshTokenState token, Instant now) {
        EncryptedSuccessor stored = tokens.findEncryptedSuccessor(institutionId, token.id())
                .orElseThrow(() -> new IllegalStateException("a token inside its grace window "
                        + "whose encrypted successor is not stored"));
        PlainRefreshToken successor = decrypt(institutionId, token.id(), stored);
        families.recordUse(institutionId, family.id(), now);

        auditLogWriter.append(entry(institutionId, auditRequestId, family, ACTION_REFRESHED,
                OUTCOME_SUCCESS, AFTER_GRACE));
        return new Attempt.Rotated(family.id(), family.accountId(), family.secondFactorVerified(),
                successor, stored.expiresAt(), RotationKind.GRACE);
    }

    /**
     * Decrypts the successor with the row that holds it as its additional authenticated data. A value
     * that does not authenticate (copied from another row, or altered) is a failure of the request, not
     * a rejection: nothing is returned and the transaction rolls back. Neither the value nor the key is
     * in the message.
     */
    private PlainRefreshToken decrypt(InstitutionId institutionId, UUID tokenId,
            EncryptedSuccessor stored) {
        try {
            byte[] plain = encryption.decrypt(SUCCESSOR_TABLE, SUCCESSOR_COLUMN, institutionId,
                    tokenId.toString(), stored.ciphertext());
            return PlainRefreshToken.of(new String(plain, StandardCharsets.UTF_8));
        } catch (AeadIntegrityException e) {
            throw new IllegalStateException("the encrypted successor of a token could not be "
                    + "authenticated", e);
        }
    }

    /**
     * A consumed token outside the grace window: the family is revoked with {@code reuse_detected},
     * the encrypted successors of its tokens are emptied and the entry of a possible leak is written,
     * all in the transaction the caller opened. The outcome is a returned value, so that transaction
     * commits and the revocation survives the rejection. Only this family is touched, and the family
     * is known to be live (the decision rejects a revoked one before it reaches here), so the
     * conditional update cannot miss under the lock.
     */
    private Attempt revokeForReuse(InstitutionId institutionId, UUID auditRequestId,
            RefreshTokenFamilyState family, Instant now) {
        if (!families.revokeForReuse(institutionId, family.id(), now)) {
            throw new IllegalStateException("a family that is live under its own lock could not "
                    + "be revoked");
        }
        tokens.clearSuccessors(institutionId, family.id());
        auditLogWriter.append(entry(institutionId, auditRequestId, family, ACTION_REUSE_DETECTED,
                OUTCOME_DENIED, AFTER_FAMILY_REVOKED));
        return new Attempt.Rejected(RefreshRejection.REUSE_DETECTED);
    }

    private Attempt rejection(InstitutionId institutionId, UUID auditRequestId,
            RefreshTokenFamilyState family, RotationDecision decision) {
        RefreshRejection reason = decision.rejection().orElseThrow();
        auditLogWriter.append(entry(institutionId, auditRequestId, family, ACTION_REJECTED,
                OUTCOME_DENIED, reasonJson(reason)));
        return new Attempt.Rejected(reason);
    }

    /** Entity: the family. No secret: not the token, not its hash, not the fingerprint, not the jti. */
    private static AuditEntry entry(InstitutionId institutionId, UUID requestId,
            RefreshTokenFamilyState family, String action, String outcome, String afterValue) {
        StaffAccountId account = family.accountId();
        return new AuditEntry(institutionId.value(), account.value(), ACTOR_KIND_STAFF,
                account.value().toString(), null, null, requestId, null, action,
                FAMILY_ENTITY_TYPE, family.id().toString(), outcome, null, afterValue, null, null);
    }

    /** A malformed text is simply not found: it cannot be the text of any issued token. */
    private static Optional<RefreshTokenHash> hashOf(String presentedToken) {
        try {
            return Optional.of(RefreshTokenHash.of(PlainRefreshToken.of(presentedToken)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    static String reasonJson(RefreshRejection reason) {
        return AuditJson.object().text("reason", reason.auditCode()).toJson();
    }

    /** What the transaction leaves behind; the access token is signed from it after the commit. */
    private sealed interface Attempt {

        record Rejected(RefreshRejection reason) implements Attempt {
        }

        record Rotated(UUID familyId, StaffAccountId accountId, boolean secondFactorVerified,
                PlainRefreshToken refreshToken, Instant refreshExpiresAt, RotationKind kind)
                implements Attempt {
        }
    }
}
