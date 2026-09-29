package com.confia.identity.application;

import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.PlainTotpSecret;
import java.util.List;
import java.util.Objects;

/**
 * Result of {@link EnrollTotpSecondFactor}: the ten MFA recovery codes in clear text, returned to
 * the caller a single time (specs/identity/spec.md, "Los diez códigos se generan y se muestran una
 * única vez": "sin que ninguna consulta posterior pueda recuperarlos en claro"). Safe to hold as a
 * plain record: each element is a {@link PlainRecoveryCode}, already redacted on its own {@code
 * toString()}, so this wrapper's generated {@code toString()} never prints a code verbatim.
 *
 * <p><b>Carries the TOTP secret as {@link PlainTotpSecret}, never as a {@link String}.</b> An
 * earlier version of this class carried no secret at all, following design.md §4.1's own flow — and
 * that was the gap the verification of this cut found: the clear-text secret lived only in a local
 * variable of the use case, so no authenticator app could ever learn it and the second factor was
 * enrolled yet impossible to activate. The secret now travels here, and it travels as the redacted
 * value object precisely so this record stays safe: a {@code String} component would be printed
 * verbatim by the generated {@code toString()}, while {@link PlainTotpSecret} redacts its own. The
 * caller renders it with {@link PlainTotpSecret#base32()}.
 *
 * <p>The {@code otpauth://} URI and its QR rendering stay out of scope on purpose: they need a
 * per-institution issuer and label, which is organization-module information and a product
 * decision, and they belong to the change that builds the enrollment screen.
 */
public record EnrollTotpSecondFactorResult(PlainTotpSecret secret,
        List<PlainRecoveryCode> recoveryCodes) {

    public EnrollTotpSecondFactorResult {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(recoveryCodes, "recoveryCodes");
        recoveryCodes = List.copyOf(recoveryCodes);
    }
}
