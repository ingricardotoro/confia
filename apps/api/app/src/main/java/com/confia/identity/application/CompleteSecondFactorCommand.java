package com.confia.identity.application;

import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.SecondFactorPurpose;
import java.util.Objects;
import java.util.UUID;

/**
 * What {@link CompleteSecondFactor} needs from its caller (session-endpoints-cookie-and-csrf design.md,
 * decision 13): who holds the restricted token, what it is for, exactly one proof as presented, and the
 * origin of the request. The account, the institution and the purpose come from the verified token and
 * from nothing the client typed.
 *
 * <p>The proof travels as text: the use case parses it, and a text that cannot be a code is an
 * incorrect proof, not an error of the caller. Exactly one of {@code totpCode} and {@code
 * recoveryCode} is present; the edge refuses a body with both or neither before it gets here, and a
 * command that carries both or neither is a defect and fails to build.
 *
 * @param accountId the staff account of the restricted token
 * @param institutionId the institution of the restricted token
 * @param purpose what the restricted token is for
 * @param totpCode the TOTP code as typed, or {@code null}
 * @param recoveryCode the recovery code as typed, or {@code null}
 * @param clientAddress the resolved address of the client, or {@code null} for a call without a
 *     request
 * @param userAgent the agent text the request filter already sanitized, or {@code null}
 * @param requestId the identifier of the request, for the entries; a blank one gets a fresh
 *     identifier
 */
public record CompleteSecondFactorCommand(UUID accountId, UUID institutionId,
        SecondFactorPurpose purpose, String totpCode, String recoveryCode,
        ClientAddress clientAddress, String userAgent, String requestId) {

    public CompleteSecondFactorCommand {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(requestId, "requestId");
        if ((totpCode == null) == (recoveryCode == null)) {
            throw new IllegalArgumentException(
                    "a second-factor command carries exactly one of a TOTP code and a recovery code");
        }
    }

    /**
     * Redacts both proofs, and the address and the agent with them: the compiler-generated {@code
     * toString()} of a record prints every component.
     */
    @Override
    public String toString() {
        return "CompleteSecondFactorCommand[accountId=" + accountId + ", institutionId=" + institutionId
                + ", purpose=" + purpose + ", totpCode=REDACTED, recoveryCode=REDACTED,"
                + " clientAddress=REDACTED, userAgent=REDACTED, requestId=" + requestId + "]";
    }
}
