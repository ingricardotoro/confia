package com.confia.identity.domain;

import com.confia.kernel.InstitutionId;
import java.util.Objects;

/**
 * The four possible outcomes of the password-authentication use case, and no more
 * (specs/identity/spec.md, requirement "Resultado tipado de la autenticación con cuatro
 * desenlaces"; column-encryption-and-mfa-totp design.md, §9, decision 6). None of the four
 * outcomes declares any authorization-scope field: that is a later change's own responsibility,
 * never guessed here.
 *
 * <p>Following {@code com.confia.shared.security.IdempotentOutcome}'s own precedent, all four
 * outcomes are nested records rather than sibling top-level files, so the sealed interface's
 * implicit {@code permits} clause and its members stay in the one file a future change edits.
 *
 * <p><b>Corrected on 2026-09-29, by {@code column-encryption-and-mfa-totp} (C4).</b> The part 1
 * version of this Javadoc promised that a future change could add an outcome — for example
 * {@code SecondFactorRequired} — "by editing this file's {@code permits} clause", after which
 * "the compiler forces every existing {@code switch}" to handle it. That promise was aspirational
 * and, at the time, not true: {@code AuthenticateWithPassword} — the only production consumer of
 * this type — decided everything through {@code instanceof Authenticated}, twice, and an
 * {@code instanceof} check does not force anything. Adding an outcome to {@code permits} without
 * touching either {@code instanceof} would have compiled cleanly and silently treated the new
 * outcome as a rejected login, in both the backoff-counter decision and the audited outcome
 * (column-encryption-and-mfa-totp proposal.md, "La costura con la parte 1"). This change is what
 * makes the promise true: it adds {@link SecondFactorRequired} and
 * {@link SecondFactorEnrollmentRequired} here, and, in the same commit, converts both of
 * {@code AuthenticateWithPassword}'s decision points to an exhaustive {@code switch} with no
 * {@code default} (design.md, decision 6). From this point on, any change that edits this file's
 * implicit {@code permits} clause without updating every existing exhaustive {@code switch} over
 * {@link AuthenticationResult} fails to compile — demonstrated permanently by {@code
 * ExhaustiveAuthenticationResultSwitchCompilationTest}'s deliberately non-exhaustive fixture.
 */
public sealed interface AuthenticationResult {

    /**
     * The credential was valid. Carries only the two identifiers the caller needs to resolve the
     * rest of the session; {@code requiredDelay} — the anti-brute-force wait this outcome may
     * still owe, even on success — travels in {@code AuthenticationDecision}, not here
     * (design.md, decision 10, "El retardo viaja en un envoltorio").
     */
    record Authenticated(StaffAccountId userId, InstitutionId institutionId)
            implements AuthenticationResult {

        public Authenticated {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(institutionId, "institutionId");
        }
    }

    /**
     * The credential was rejected. {@code reason} is internal: it is written to the audit log's
     * {@code after_value} and never travels to whoever called the use case
     * (specs/identity/spec.md, "ningún campo del resultado expuesto a quien llama distingue entre
     * los dos motivos").
     */
    record Rejected(RejectionReason reason) implements AuthenticationResult {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The credential was valid, the account has {@code mfa_required = true}, and it already has a
     * TOTP secret enrolled: pending a valid TOTP code before this login completes
     * (column-encryption-and-mfa-totp, specs/identity/spec.md, "MFA obligatoria para cuentas
     * marcadas con {@code mfa_required}"). Carries only the two identifiers the caller needs to
     * challenge for a code; translating this outcome into a session token that can only call the
     * TOTP-verification endpoint is {@code session-tokens-and-web-layer}'s job, the fourth change of
     * this sequence — this change decides and returns the typed outcome, and issues no token
     * (proposal.md, "Cómo se determina qué cuenta necesita segundo factor").
     *
     * <p>Neither this outcome nor {@link SecondFactorEnrollmentRequired} is a rejected login: a
     * pending second factor never advances the failed-attempt backoff counter, and is never
     * audited as a failure (specs/identity/spec.md, "{@code SecondFactorRequired} no avanza el
     * contador de retroceso ni se audita como fallo").
     */
    record SecondFactorRequired(StaffAccountId userId, InstitutionId institutionId)
            implements AuthenticationResult {

        public SecondFactorRequired {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(institutionId, "institutionId");
        }
    }

    /**
     * The credential was valid, the account has {@code mfa_required = true}, but it has no TOTP
     * secret enrolled yet: pending enrollment before this login completes
     * (column-encryption-and-mfa-totp, specs/identity/spec.md, "MFA obligatoria para cuentas
     * marcadas con {@code mfa_required}"). Carries only the two identifiers the caller needs to
     * start enrollment; translating this outcome into a session token restricted to the enrollment
     * endpoint is {@code session-tokens-and-web-layer}'s job — this change issues no token.
     *
     * <p>Like {@link SecondFactorRequired}, never advances the failed-attempt backoff counter and
     * is never audited as a failure: the credential itself was correct.
     */
    record SecondFactorEnrollmentRequired(StaffAccountId userId, InstitutionId institutionId)
            implements AuthenticationResult {

        public SecondFactorEnrollmentRequired {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(institutionId, "institutionId");
        }
    }

    /**
     * The two internal rejection causes this change produces. Never serialized to a caller-visible
     * field (design.md, decision 8, point 2).
     */
    enum RejectionReason {
        INVALID_PASSWORD,
        ACCOUNT_NOT_FOUND
    }
}
