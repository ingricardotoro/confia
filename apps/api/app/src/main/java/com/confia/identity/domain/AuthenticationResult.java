package com.confia.identity.domain;

import com.confia.kernel.InstitutionId;
import java.util.Objects;

/**
 * The two possible outcomes of the password-authentication use case, and no more
 * (specs/identity/spec.md, requirement "Resultado tipado de la autenticación con dos desenlaces";
 * design.md, decision 10). Neither outcome declares any authorization-scope field: that is a
 * later change's own responsibility, never guessed here.
 *
 * <p>Following {@code com.confia.shared.security.IdempotentOutcome}'s own precedent, both
 * outcomes are nested records rather than sibling top-level files, so the sealed interface's
 * {@code permits} clause and its two members stay in the one file a future change edits.
 * {@code mfa-totp-and-password-recovery} adds its own outcome (for example
 * {@code SecondFactorRequired}) by editing this file's {@code permits} clause; the compiler then
 * forces every existing {@code switch} over {@link AuthenticationResult} to handle it, which is
 * exactly why this is safe to extend later (design.md, decision 10).
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
     * The two internal rejection causes this change produces. Never serialized to a caller-visible
     * field (design.md, decision 8, point 2).
     */
    enum RejectionReason {
        INVALID_PASSWORD,
        ACCOUNT_NOT_FOUND
    }
}
