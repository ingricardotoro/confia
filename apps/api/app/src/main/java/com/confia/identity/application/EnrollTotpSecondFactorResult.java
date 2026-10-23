package com.confia.identity.application;

import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.PlainTotpSecret;
import java.util.List;
import java.util.Objects;

/**
 * Result of {@link EnrollTotpSecondFactor}: either the credential was written and the secret and the
 * ten recovery codes travel back to the caller a single time, or the account already has a verified
 * credential and nothing was written (session-endpoints-cookie-and-csrf design.md, decision 13;
 * specs/identity/spec.md, "Los diez códigos se generan y se muestran una única vez": "sin que ninguna
 * consulta posterior pueda recuperarlos en claro").
 *
 * <p><b>The secret travels as {@link PlainTotpSecret}, never as a {@link String}.</b> The clear-text
 * secret has to reach the caller or no authenticator app could ever learn it and the second factor
 * would be enrolled yet impossible to activate. It travels as the redacted value object so the
 * generated {@code toString()} of the record stays safe: a {@code String} component would be printed
 * verbatim, while {@link PlainTotpSecret} and each {@link PlainRecoveryCode} redact their own. The
 * caller renders the secret with {@link PlainTotpSecret#base32()}.
 *
 * <p>The {@code otpauth://} URI and its QR rendering stay out of scope on purpose: they need a
 * per-institution issuer and label, which is organization-module information and a product
 * decision, and they belong to the change that builds the enrollment screen.
 */
public sealed interface EnrollTotpSecondFactorResult {

    /**
     * The credential was created, or its unverified secret was replaced.
     *
     * @param secret the new secret, in clear text, for this one response
     * @param recoveryCodes the ten new codes, in clear text, for this one response
     * @param replacedUnverified whether this enrollment replaced a credential that was never verified
     */
    record Enrolled(PlainTotpSecret secret, List<PlainRecoveryCode> recoveryCodes,
            boolean replacedUnverified) implements EnrollTotpSecondFactorResult {

        public Enrolled {
            Objects.requireNonNull(secret, "secret");
            Objects.requireNonNull(recoveryCodes, "recoveryCodes");
            recoveryCodes = List.copyOf(recoveryCodes);
        }

        /** The secret as the authenticator application takes it, for the layer that answers the client. */
        public String secretBase32() {
            return secret.base32();
        }

        /** The ten codes as text, for the layer that answers the client. */
        public List<String> recoveryCodeTexts() {
            return recoveryCodes.stream().map(PlainRecoveryCode::value).toList();
        }
    }

    /** The account has a verified credential: nothing was written but the entry that says so. */
    record AlreadyEnrolled() implements EnrollTotpSecondFactorResult {
    }
}
