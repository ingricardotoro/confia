package com.confia.identity.application;

import com.confia.identity.domain.PlainRecoveryCode;
import java.util.List;
import java.util.Objects;

/**
 * Result of {@link EnrollTotpSecondFactor}: the ten MFA recovery codes in clear text, returned to
 * the caller a single time (specs/identity/spec.md, "Los diez códigos se generan y se muestran una
 * única vez": "sin que ninguna consulta posterior pueda recuperarlos en claro"). Safe to hold as a
 * plain record: each element is a {@link PlainRecoveryCode}, already redacted on its own {@code
 * toString()}, so this wrapper's generated {@code toString()} never prints a code verbatim.
 *
 * <p>Deliberately carries no field for the TOTP secret itself: design.md §4.1's own flow returns
 * only the ten codes, not the secret, to this call's caller. Provisioning UI (a QR code, or the
 * secret typed manually) is out of scope for this cut — it belongs to whichever change first
 * builds the enrollment screen, not to this use case.
 */
public record EnrollTotpSecondFactorResult(List<PlainRecoveryCode> recoveryCodes) {

    public EnrollTotpSecondFactorResult {
        Objects.requireNonNull(recoveryCodes, "recoveryCodes");
        recoveryCodes = List.copyOf(recoveryCodes);
    }
}
