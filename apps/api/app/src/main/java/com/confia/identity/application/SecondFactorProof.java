package com.confia.identity.application;

import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.TotpCode;
import java.util.Objects;

/**
 * The second factor presented with a password reset, if any (password-recovery-token design.md
 * decision 7). It is only checked when the account has active MFA; otherwise it is ignored and no
 * verifier runs. Both carried values print redacted.
 */
public sealed interface SecondFactorProof {

    record None() implements SecondFactorProof {
    }

    record Totp(TotpCode code) implements SecondFactorProof {

        public Totp {
            Objects.requireNonNull(code, "code");
        }
    }

    record RecoveryCode(PlainRecoveryCode code) implements SecondFactorProof {

        public RecoveryCode {
            Objects.requireNonNull(code, "code");
        }
    }
}
