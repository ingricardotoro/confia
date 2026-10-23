package com.confia.identity.application;

import com.confia.identity.application.SecondFactorChallenge.Kind;
import com.confia.identity.domain.AuthenticationResult;
import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.AuthenticationResult.Rejected;
import com.confia.identity.domain.AuthenticationResult.SecondFactorEnrollmentRequired;
import com.confia.identity.domain.AuthenticationResult.SecondFactorRequired;
import com.confia.identity.domain.FailureSpreadWindow;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.observability.events.SecurityEventLog;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.security.token.AccessToken;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.MfaPurpose;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * The login (session-endpoints-cookie-and-csrf design.md decisions 10, 11 and 12): the password
 * authentication and, when the password is right and no second factor is owed, the issuance of the
 * session, composed in <b>one</b> transaction opened by {@link TransactionRunner}. A failure of the
 * entries, of the family or of the token rolls back all of them, so the trail never says a login
 * succeeded for a session that does not exist.
 *
 * <p><b>Inside the transaction.</b> {@link AuthenticateWithPassword#runWithinTransaction} decides
 * the outcome and writes its entries and its backoff. Only {@link Authenticated} goes on to {@link
 * IssueSession#runWithinTransaction}, which locks the account, creates the family and its first
 * token and writes the {@code identity.session.started} entry. Only {@link Rejected} reaches the
 * spread signal: it records the failure of the identifier fingerprint from the address of the
 * request and, on the call that brings five distinct identifiers inside ten minutes, writes {@code
 * identity.login.failure_spread_detected} in this same transaction. A password that was right, with
 * or without a second factor pending, is never a failure.
 *
 * <p><b>After the commit.</b> The access token or the restricted token is signed, and the {@code
 * WARN} of the signal is written: nothing is signed or announced for a transaction that did not
 * commit. The restricted token (purpose {@code mfa-verify} or {@code mfa-enroll}) creates no family
 * and no refresh token.
 *
 * <p><b>The institution</b> comes from {@link LoginInstitutionProvider} only, never from the request.
 * The guard of {@link AuthenticateWithPassword#execute} is repeated here before the transaction is
 * opened, because this class composes the body that guard protects and does not go through it: a
 * provider that answers a different institution from one read to the next is a wiring defect, never
 * a rejected login.
 *
 * <p><b>Never waits</b> (specs/identity/spec.md, "Ninguna clase del módulo de identidad espera"):
 * {@link LoginDecision#requiredDelay()} is returned for the edge to materialize.
 *
 * <p>The signal is recorded in memory inside the transaction, so a retry of the transaction records
 * the same identifier again, which {@link FailureSpreadWindow} ignores. The cost is that a retry
 * after the call that raised the signal does not raise it a second time; it is a signal, not a
 * control, and the entry of the first attempt is rolled back with it.
 */
public final class LogIn {

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String UNKNOWN_ACCOUNT_ACTOR_LABEL = "unknown-account";
    private static final String CLIENT_ADDRESS_ENTITY_TYPE = "identity.client_address";
    private static final String ACTION_FAILURE_SPREAD = "identity.login.failure_spread_detected";
    private static final String OUTCOME_DENIED = "denied";

    private final TransactionRunner transactionRunner;
    private final LoginInstitutionProvider institutionProvider;
    private final AuthenticateWithPassword authenticate;
    private final IssueSession issueSession;
    private final AccessTokenIssuer tokens;
    private final LoginIdentifierFingerprinter fingerprinter;
    private final LoginFailureSpread failureSpread;
    private final AuditLogWriter auditLogWriter;
    private final SecurityEventLog eventLog;
    private final Clock clock;

    public LogIn(TransactionRunner transactionRunner, LoginInstitutionProvider institutionProvider,
            AuthenticateWithPassword authenticate, IssueSession issueSession, AccessTokenIssuer tokens,
            LoginIdentifierFingerprinter fingerprinter, LoginFailureSpread failureSpread,
            AuditLogWriter auditLogWriter, SecurityEventLog eventLog, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.institutionProvider = Objects.requireNonNull(institutionProvider, "institutionProvider");
        this.authenticate = Objects.requireNonNull(authenticate, "authenticate");
        this.issueSession = Objects.requireNonNull(issueSession, "issueSession");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.failureSpread = Objects.requireNonNull(failureSpread, "failureSpread");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LoginDecision execute(LoginCommand command) {
        Objects.requireNonNull(command, "command");
        if (!namesAnAccountAtAll(command)) {
            // Nothing to look up, to count or to audit, and no wait: the answer is the one of any
            // other rejection. It tells nothing about accounts, because no account can have such an
            // identifier.
            return new LoginDecision(new LoginOutcome.Failed(), Duration.ZERO);
        }
        UUID requestId = command.requestId().isBlank() ? UUID.randomUUID()
                : UUID.fromString(command.requestId());
        InstitutionId institutionId = institutionProvider.loginInstitutionId();
        SecurityContext context = new SecurityContext("", ACTOR_KIND_STAFF,
                institutionId.value().toString(), requestId.toString());
        if (!institutionId.equals(institutionProvider.loginInstitutionId())) {
            throw new IllegalStateException("login institution mismatch: the configured login "
                    + "institution changed while the login was being prepared — this is a wiring "
                    + "defect, never a rejected login");
        }

        Committed committed = transactionRunner.execute(context,
                () -> runWithinTransaction(institutionId, requestId, command));

        if (committed.spreadDetected()) {
            eventLog.warn(ACTION_FAILURE_SPREAD, requestId);
        }
        return new LoginDecision(outcomeOf(institutionId, command, committed),
                committed.authentication().requiredDelay());
    }

    /**
     * Whether the identifier and the password survive their own normalization: an identifier of the
     * length of an account after trimming and folding, and a password that is not empty and not too
     * long once folded. The edge limits the raw lengths, but normalization can shrink an identifier
     * under its minimum or grow a password over its maximum, and the domain types refuse both with an
     * exception that must never become a {@code 500}.
     */
    private static boolean namesAnAccountAtAll(LoginCommand command) {
        try {
            LoginIdentifier.of(command.identifier());
            PlainPassword.of(command.password());
            return true;
        } catch (IllegalArgumentException unusable) {
            return false;
        }
    }

    private Committed runWithinTransaction(InstitutionId institutionId, UUID requestId,
            LoginCommand command) {
        AuthenticationDecision decision = authenticate.runWithinTransaction(institutionId,
                requestId.toString(), new AuthenticationCommand(command.identifier(),
                        command.password(), command.clientAddress(), command.userAgent()));
        return switch (decision.result()) {
            case Authenticated authenticated -> new Committed(decision,
                    issueSession.runWithinTransaction(institutionId, requestId.toString(),
                            new IssueSessionCommand(authenticated.userId(), false,
                                    command.userAgent(), command.clientAddress())),
                    false);
            case Rejected rejected -> new Committed(decision, null,
                    recordFailure(institutionId, requestId, command));
            case SecondFactorRequired required -> new Committed(decision, null, false);
            case SecondFactorEnrollmentRequired enrollmentRequired ->
                    new Committed(decision, null, false);
        };
    }

    /**
     * Records the rejected attempt for the spread signal and, when it raises the signal, writes the
     * entry. Without an address there is nobody to count the failure for.
     */
    private boolean recordFailure(InstitutionId institutionId, UUID requestId, LoginCommand command) {
        ClientAddress client = command.clientAddress();
        if (client == null) {
            return false;
        }
        IdentifierFingerprint fingerprint = fingerprinter.fingerprintOf(
                LoginIdentifier.of(command.identifier()));
        if (!failureSpread.recordFailure(client, fingerprint, clock.instant())) {
            return false;
        }
        auditLogWriter.append(new AuditEntry(institutionId.value(), null, ACTOR_KIND_STAFF,
                UNKNOWN_ACCOUNT_ACTOR_LABEL, client.canonical(), command.userAgent(), requestId, null,
                ACTION_FAILURE_SPREAD, CLIENT_ADDRESS_ENTITY_TYPE, client.canonical(), OUTCOME_DENIED,
                null, spreadAfterValue(), null, null));
        return true;
    }

    /** The {@code after_value} of the spread entry: how many identifiers, in how many seconds. */
    static String spreadAfterValue() {
        return AuditJson.object().number("distinctIdentifiers", FailureSpreadWindow.THRESHOLD)
                .number("windowSeconds", FailureSpreadWindow.WINDOW.toSeconds()).toJson();
    }

    /** After the commit: signs what the outcome calls for. */
    private LoginOutcome outcomeOf(InstitutionId institutionId, LoginCommand command,
            Committed committed) {
        AuthenticationResult result = committed.authentication().result();
        return switch (result) {
            case Authenticated authenticated -> {
                IssueSession.Stored stored = committed.stored();
                IssuedSession issued = issueSession.signAfterCommit(institutionId,
                        new IssueSessionCommand(authenticated.userId(), false, command.userAgent(),
                                command.clientAddress()), stored);
                yield new LoginOutcome.Started(new SessionGrant(issued.accessToken().compact(),
                        issued.accessExpiresAt(), issued.refreshToken().value(),
                        stored.absoluteExpiresAt(), stored.idleExpiresAt()));
            }
            case SecondFactorRequired required -> challenge(institutionId, required.userId(),
                    Kind.VERIFICATION_REQUIRED, MfaPurpose.MFA_VERIFY);
            case SecondFactorEnrollmentRequired enrollmentRequired -> challenge(institutionId,
                    enrollmentRequired.userId(), Kind.ENROLLMENT_REQUIRED, MfaPurpose.MFA_ENROLL);
            case Rejected rejected -> new LoginOutcome.Failed();
        };
    }

    private LoginOutcome challenge(InstitutionId institutionId, StaffAccountId accountId, Kind kind,
            MfaPurpose purpose) {
        AccessToken token = tokens.issueMfa(accountId.value(), institutionId.value(), purpose);
        return new LoginOutcome.ChallengeIssued(new SecondFactorChallenge(kind, token.compact(),
                token.expiresAt()));
    }

    /** What the transaction leaves behind for the half that runs after the commit. */
    private record Committed(AuthenticationDecision authentication, IssueSession.Stored stored,
            boolean spreadDetected) {
    }
}
