package com.confia.identity.application;

import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.RefreshTokenFamily;
import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.RefreshTokenRow;
import com.confia.identity.domain.SessionLifetimePolicy;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.security.token.AccessToken;
import com.confia.shared.security.token.AccessTokenIssuer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Issues a session: a family of refresh tokens, its first token and the audit entry that says so
 * (session-tokens-and-web-layer design.md decision 9; scenarios I87 to I90, I119, I120 and I126). In
 * this change only tests invoke it; the endpoint that does is {@code
 * session-endpoints-cookie-and-csrf}'s.
 *
 * <p>One transaction, opened by {@link TransactionRunner}, holds everything that is stored: the
 * lock of the account, the family, the token and the entry. If any of them fails the exception rolls
 * all of them back. The access token is signed <b>after</b> the commit, so a token for a family
 * that never committed is never handed out; the cost, a committed family whose signing failed, is a
 * session nobody holds, which expires on its own.
 *
 * <p><b>The account lock comes first</b> (review of PR 9, I-2). With READ COMMITTED, locking the
 * families of an account does not stop an issuance from inserting a new one while the account's
 * password is being reset, and that family would stay alive with the old password. The issuance
 * therefore takes the same {@code FOR UPDATE} on the account row that the reset takes, before it
 * writes anything: the lock order is always account, then families. The instant of the session is
 * read after the lock, so it belongs to the moment the issuance actually runs.
 *
 * <p>The origin of the client is explicit in the command. The origin in the audit entry is completed
 * by the request decorator of the audit writer, so this class leaves {@code sourceIp} and {@code
 * userAgent} of the entry empty.
 */
public final class IssueSession {

    private static final String ACTION_STARTED = "identity.session.started";
    private static final String FAMILY_ENTITY_TYPE = "identity.refresh_token_family";
    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private final TransactionRunner transactionRunner;
    private final StaffAccountRepository accounts;
    private final RefreshTokenFamilyRepository families;
    private final RefreshTokenRepository tokens;
    private final AccessTokenIssuer accessTokens;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final SecureRandom random;
    private final SessionLifetimePolicy lifetimes;

    public IssueSession(TransactionRunner transactionRunner, StaffAccountRepository accounts,
            RefreshTokenFamilyRepository families, RefreshTokenRepository tokens,
            AccessTokenIssuer accessTokens, AuditLogWriter auditLogWriter, Clock clock,
            SecureRandom random) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.families = Objects.requireNonNull(families, "families");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.accessTokens = Objects.requireNonNull(accessTokens, "accessTokens");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.lifetimes = new SessionLifetimePolicy();
    }

    public IssuedSession execute(SecurityContext context, IssueSessionCommand command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");

        InstitutionId institutionId = new InstitutionId(UUID.fromString(context.institutionId()));
        Stored stored = transactionRunner.execute(context,
                () -> runWithinTransaction(institutionId, context.requestId(), command));

        // After the commit: nothing is signed for a family that did not commit.
        AccessToken access = accessTokens.issueAccess(command.accountId().value(),
                institutionId.value(), stored.familyId(), methodsOf(command));
        return new IssuedSession(stored.familyId(), stored.refreshToken(), stored.refreshExpiresAt(),
                access, access.expiresAt());
    }

    /**
     * The stored half of the issuance, for the caller that composes it with its own transaction
     * ({@code VerifyTotpCode}'s pattern). Package-private until that composition exists.
     */
    Stored runWithinTransaction(InstitutionId institutionId, String requestId,
            IssueSessionCommand command) {
        StaffAccountId accountId = command.accountId();
        // 1. The account lock, before anything is written: account, then families.
        StaffAccount account = accounts.lockById(institutionId, accountId).orElseThrow(
                () -> new IllegalStateException("a session cannot be issued to an account that "
                        + "does not exist"));

        Instant now = clock.instant();
        UUID familyId = UUID.randomUUID();
        PlainRefreshToken refreshToken = PlainRefreshToken.generate(random);
        Instant absoluteExpiresAt = lifetimes.absoluteExpiresAt(now);
        Instant refreshExpiresAt = lifetimes.refreshExpiresAt(now, absoluteExpiresAt);

        // 2. The family and its first token.
        families.insert(institutionId, new RefreshTokenFamily(familyId, account.id(),
                command.secondFactorVerified(), UserAgentFingerprint.of(command.userAgent()),
                command.clientAddress() == null ? null : command.clientAddress().canonical(), now,
                now, absoluteExpiresAt));
        tokens.insert(institutionId, new RefreshTokenRow(UUID.randomUUID(), familyId,
                RefreshTokenHash.of(refreshToken), now, refreshExpiresAt));

        // 3. The entry, in the same transaction. No secret: not the token, not its hash.
        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
        auditLogWriter.append(new AuditEntry(institutionId.value(), accountId.value(),
                ACTOR_KIND_STAFF, accountId.value().toString(), null, null, auditRequestId, null,
                ACTION_STARTED, FAMILY_ENTITY_TYPE, familyId.toString(), OUTCOME_SUCCESS, null,
                "{\"secondFactor\":" + command.secondFactorVerified() + "}", null, null));
        return new Stored(familyId, refreshToken, refreshExpiresAt);
    }

    private static Set<AuthenticationMethod> methodsOf(IssueSessionCommand command) {
        return command.secondFactorVerified()
                ? EnumSet.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD)
                : EnumSet.of(AuthenticationMethod.PASSWORD);
    }

    /** What the transaction leaves behind; the access token is signed from it after the commit. */
    record Stored(UUID familyId, PlainRefreshToken refreshToken, Instant refreshExpiresAt) {
    }
}
