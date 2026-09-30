package com.confia.identity.application;

import com.confia.identity.domain.AuthenticationResult;
import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.AuthenticationResult.Rejected;
import com.confia.identity.domain.AuthenticationResult.RejectionReason;
import com.confia.identity.domain.AuthenticationResult.SecondFactorEnrollmentRequired;
import com.confia.identity.domain.AuthenticationResult.SecondFactorRequired;
import com.confia.identity.domain.BackoffPolicy;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StaffAccount;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates a staff member with a password (design.md, §11 paso 15; §4, §6.2, §6.3, decision
 * 9). Not a Spring bean: no annotation registers it, and this cut wires no adapter for the three
 * ports {@code identity.infrastructure} still owes — {@link StaffAccountRepository}, {@link
 * LoginBackoffStore} and {@link LoginInstitutionProvider} — those real jOOQ/configuration adapters
 * are PR C3b's own task 4.1.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): {@link #execute} delegates
 * the whole body to {@link TransactionRunner#execute(SecurityContext, java.util.function.Supplier)},
 * satisfying the rule that confines transaction-opening code to {@code shared/security} by
 * composition, the same pattern {@link com.confia.shared.security.IdempotentExecutor} already
 * established.
 *
 * <p><b>The closing guard (design.md, decision 11).</b> If the institution the caller's {@link
 * SecurityContext} carries does not match {@link LoginInstitutionProvider#loginInstitutionId()},
 * this is a wiring defect or an attack, never a business outcome: it fails loudly with an
 * unchecked exception, and NEVER produces {@link Rejected} — confusing the two would hide exactly
 * the class of bug this guard exists to surface. The guard runs before {@link
 * TransactionRunner#execute} even opens a connection, so a mismatched request never reaches the
 * database at all.
 *
 * <p><b>Never waits</b> (design.md, decision 1; specs/identity/spec.md, "Ninguna clase del módulo
 * de identidad espera", enforced by {@code NoBlockingWaitInIdentityTest}). This class computes
 * {@code requiredDelay} and returns it inside {@link AuthenticationDecision} once its transaction
 * has already committed; materializing the wait is the caller's job (design.md, decision 1's
 * six-point contract, {@link AuthenticationDecision}'s own Javadoc).
 *
 * <p><b>The stored hash is never recalculated</b> (specs/identity/spec.md, "El hash almacenado no
 * se recalcula"): {@link #runWithinTransaction} never calls {@link PasswordHasher#hash}; that
 * method's only production caller in this whole change is the decoy {@link
 * com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher} builds at its own
 * construction.
 *
 * <p><b>The second-factor seam (column-encryption-and-mfa-totp, C4).</b> Once the presented
 * password matches, and only then, this class reads {@code mfa_required} off the resolved {@link
 * StaffAccount} and, when it is {@code true}, whether {@link #totpCredentials} already has a
 * secret enrolled for that account (proposal.md, "Cómo se determina qué cuenta necesita segundo
 * factor"): {@code false} still produces {@link Authenticated}, unchanged from part 1; {@code
 * true} with a secret enrolled produces {@link SecondFactorRequired}; {@code true} with none
 * produces {@link SecondFactorEnrollmentRequired}. A wrong password is decided first and never
 * reaches this reading, so a wrong password never discloses whether an account requires a second
 * factor. Both {@link #decideBackoffState} and {@link #auditOutcomeOf} are exhaustive {@code
 * switch} expressions with no {@code default} over {@link AuthenticationResult}'s four outcomes
 * (design.md, decision 6): the two production points the part 1 Javadoc of {@link
 * AuthenticationResult} used to describe as {@code instanceof Authenticated}, corrected in this
 * same commit.
 */
public final class AuthenticateWithPassword {

    private static final String UNKNOWN_ACCOUNT_ACTOR_LABEL = "unknown-account";
    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String STAFF_ACCOUNT_ENTITY_TYPE = "identity.staff_account";
    private static final String LOGIN_BACKOFF_ENTITY_TYPE = "identity.login_backoff";
    private static final String ACTION_LOGIN_SUCCEEDED = "identity.login.succeeded";
    private static final String ACTION_LOGIN_FAILED = "identity.login.failed";
    private static final String ACTION_LOGIN_SECOND_FACTOR_REQUIRED =
            "identity.login.second_factor_required";
    private static final String ACTION_LOGIN_SECOND_FACTOR_ENROLLMENT_REQUIRED =
            "identity.login.second_factor_enrollment_required";
    private static final String ACTION_BACKOFF_APPLIED = "identity.login.backoff_applied";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_DENIED = "denied";

    private final TransactionRunner transactionRunner;
    private final LoginInstitutionProvider institutionProvider;
    private final StaffAccountRepository accounts;
    private final TotpCredentialRepository totpCredentials;
    private final LoginBackoffStore backoffStore;
    private final PasswordHasher passwordHasher;
    private final LoginIdentifierFingerprinter fingerprinter;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final BackoffPolicy backoffPolicy;

    public AuthenticateWithPassword(TransactionRunner transactionRunner,
            LoginInstitutionProvider institutionProvider, StaffAccountRepository accounts,
            TotpCredentialRepository totpCredentials, LoginBackoffStore backoffStore,
            PasswordHasher passwordHasher, LoginIdentifierFingerprinter fingerprinter,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.institutionProvider = Objects.requireNonNull(institutionProvider, "institutionProvider");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.totpCredentials = Objects.requireNonNull(totpCredentials, "totpCredentials");
        this.backoffStore = Objects.requireNonNull(backoffStore, "backoffStore");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.backoffPolicy = new BackoffPolicy();
    }

    public AuthenticationDecision execute(SecurityContext context, AuthenticationCommand command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");

        InstitutionId contextInstitutionId = new InstitutionId(UUID.fromString(context.institutionId()));
        InstitutionId configuredInstitutionId = institutionProvider.loginInstitutionId();
        if (!contextInstitutionId.equals(configuredInstitutionId)) {
            // Design.md, decision 11: a programming error or an attack, never Rejected.
            throw new IllegalStateException(
                    "authentication institution mismatch: the security context declares a "
                            + "different institution than the configured login institution — this "
                            + "is a wiring defect, never a rejected login");
        }

        return transactionRunner.execute(context,
                () -> runWithinTransaction(contextInstitutionId, context.requestId(), command));
    }

    /**
     * The seven steps design.md decision 9 draws inside {@code TransactionRunner.execute(...)}'s
     * own box, isolated into their own package-private method precisely so {@code
     * AuthenticateWithPasswordTest} can exercise this exact sequence with the five application
     * ports doubled and no real transaction at all (design.md §11, paso 15): {@link #execute} is
     * the only production caller, always from inside the transaction {@link
     * TransactionRunner#execute} already opened.
     */
    AuthenticationDecision runWithinTransaction(InstitutionId institutionId, String requestId,
            AuthenticationCommand command) {
        LoginIdentifier identifier = LoginIdentifier.of(command.presentedIdentifier());
        PlainPassword password = PlainPassword.of(command.presentedPassword());
        IdentifierFingerprint fingerprint = fingerprinter.fingerprintOf(identifier);
        Instant now = clock.instant();

        BackoffState priorState = backoffStore.claim(institutionId, fingerprint, now);
        int attemptOrdinal = backoffPolicy.attemptOrdinal(priorState, now);
        Duration requiredDelay = backoffPolicy.delayFor(attemptOrdinal);

        Optional<StaffAccount> account = accounts.findBy(institutionId, identifier);

        AuthenticationResult result;
        UUID actorId;
        String actorLabel;
        if (account.isPresent()) {
            StaffAccount staffAccount = account.get();
            boolean credentialsMatch = passwordHasher.matches(password, staffAccount.passwordHash());
            actorId = staffAccount.id().value();
            actorLabel = identifier.value();
            result = credentialsMatch
                    ? outcomeForValidCredentials(staffAccount, institutionId)
                    : new Rejected(RejectionReason.INVALID_PASSWORD);
        } else {
            // Uniform-cost verification against the decoy, whether or not the presented password
            // happens to match it (design.md, decision 7; specs/identity/spec.md, "Verificación
            // Argon2id contra un hash señuelo...").
            passwordHasher.matches(password, passwordHasher.decoyHash());
            actorId = null;
            actorLabel = UNKNOWN_ACCOUNT_ACTOR_LABEL;
            result = new Rejected(RejectionReason.ACCOUNT_NOT_FOUND);
        }

        BackoffState newState = decideBackoffState(result, attemptOrdinal, now);
        backoffStore.save(institutionId, fingerprint, newState);

        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
        auditLogWriter.append(outcomeEntry(institutionId, actorId, actorLabel, fingerprint,
                auditRequestId, result));
        if (requiredDelay.compareTo(Duration.ZERO) > 0) {
            auditLogWriter.append(backoffCycleEntry(institutionId, actorId, actorLabel, fingerprint,
                    auditRequestId, attemptOrdinal, requiredDelay));
        }

        return new AuthenticationDecision(result, requiredDelay);
    }

    /**
     * Design.md, decision (proposal.md, "Cómo se determina qué cuenta necesita segundo factor"):
     * inserted after the password already matched, and never before — a wrong password is decided
     * in {@link #runWithinTransaction} before this method is ever called, so it never discloses
     * whether an account requires a second factor.
     */
    private AuthenticationResult outcomeForValidCredentials(StaffAccount staffAccount,
            InstitutionId institutionId) {
        if (!staffAccount.mfaRequired()) {
            return new Authenticated(staffAccount.id(), institutionId);
        }
        boolean secretAlreadyEnrolled =
                totpCredentials.findByAccountId(institutionId, staffAccount.id()).isPresent();
        return secretAlreadyEnrolled
                ? new SecondFactorRequired(staffAccount.id(), institutionId)
                : new SecondFactorEnrollmentRequired(staffAccount.id(), institutionId);
    }

    /**
     * The first of the two exhaustive {@code switch} expressions design.md decision 6 requires,
     * with no {@code default}: only {@link Rejected} is a failed attempt for backoff purposes.
     * Neither {@link SecondFactorRequired} nor {@link SecondFactorEnrollmentRequired} advances the
     * counter — the credential itself was correct (specs/identity/spec.md, "{@code
     * SecondFactorRequired} no avanza el contador de retroceso ni se audita como fallo").
     */
    private BackoffState decideBackoffState(AuthenticationResult result, int attemptOrdinal,
            Instant now) {
        return switch (result) {
            case Authenticated authenticated -> backoffPolicy.afterSuccess(now);
            case SecondFactorRequired secondFactorRequired -> backoffPolicy.afterSuccess(now);
            case SecondFactorEnrollmentRequired secondFactorEnrollmentRequired ->
                    backoffPolicy.afterSuccess(now);
            case Rejected rejected -> backoffPolicy.afterFailure(attemptOrdinal, now);
        };
    }

    /** Design.md, decision 8: the login-succeeded, login-failed or pending-second-factor audit row. */
    private static AuditEntry outcomeEntry(InstitutionId institutionId, UUID actorId,
            String actorLabel, IdentifierFingerprint fingerprint, UUID requestId,
            AuthenticationResult result) {
        AuditOutcome auditOutcome = auditOutcomeOf(result);
        return new AuditEntry(institutionId.value(), actorId, ACTOR_KIND_STAFF, actorLabel, null,
                null, requestId, null, auditOutcome.action(), STAFF_ACCOUNT_ENTITY_TYPE,
                fingerprint.value(), auditOutcome.outcome(), null, auditOutcome.afterValue(), null,
                null);
    }

    /**
     * The second of the two exhaustive {@code switch} expressions design.md decision 6 requires,
     * with no {@code default}. Only {@link Rejected} is audited as a failure: the other three
     * outcomes all follow from a correct password, and each gets its own action name so the audit
     * trail distinguishes a completed login from one still pending a second factor.
     */
    private static AuditOutcome auditOutcomeOf(AuthenticationResult result) {
        return switch (result) {
            case Authenticated authenticated ->
                    new AuditOutcome(ACTION_LOGIN_SUCCEEDED, OUTCOME_SUCCESS, null);
            case SecondFactorRequired secondFactorRequired ->
                    new AuditOutcome(ACTION_LOGIN_SECOND_FACTOR_REQUIRED, OUTCOME_SUCCESS, null);
            case SecondFactorEnrollmentRequired secondFactorEnrollmentRequired -> new AuditOutcome(
                    ACTION_LOGIN_SECOND_FACTOR_ENROLLMENT_REQUIRED, OUTCOME_SUCCESS, null);
            case Rejected rejected -> new AuditOutcome(ACTION_LOGIN_FAILED, OUTCOME_DENIED,
                    rejectionReasonJson(rejected.reason()));
        };
    }

    /** The three fields {@link #outcomeEntry} needs, computed together by {@link #auditOutcomeOf}. */
    private record AuditOutcome(String action, String outcome, String afterValue) {
    }

    /** Design.md, decision 8: the backoff-cycle audit row, written only when {@code requiredDelay > 0}. */
    private static AuditEntry backoffCycleEntry(InstitutionId institutionId, UUID actorId,
            String actorLabel, IdentifierFingerprint fingerprint, UUID requestId, int attemptOrdinal,
            Duration requiredDelay) {
        String afterValue = "{\"consecutiveFailures\":" + attemptOrdinal + ",\"delaySeconds\":"
                + requiredDelay.toSeconds() + "}";
        return new AuditEntry(institutionId.value(), actorId, ACTOR_KIND_STAFF, actorLabel, null,
                null, requestId, null, ACTION_BACKOFF_APPLIED, LOGIN_BACKOFF_ENTITY_TYPE,
                fingerprint.value(), OUTCOME_SUCCESS, null, afterValue, null, null);
    }

    private static String rejectionReasonJson(RejectionReason reason) {
        String kebabCase = switch (reason) {
            case INVALID_PASSWORD -> "invalid-password";
            case ACCOUNT_NOT_FOUND -> "account-not-found";
        };
        return "{\"reason\":\"" + kebabCase + "\"}";
    }
}
