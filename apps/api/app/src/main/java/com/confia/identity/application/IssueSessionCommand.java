package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.shared.security.ClientAddress;
import java.util.Objects;

/**
 * What a session issuance needs from its caller (session-tokens-and-web-layer design.md decision 9).
 * The origin is explicit and never ambient (observation O6): the caller states the agent and the
 * address of the client, and a test or a background caller states none. The institution is not a
 * field: it comes from the security context, as in every other use case of this module.
 *
 * @param accountId the account that authenticated
 * @param secondFactorVerified whether it completed the second factor, which decides the {@code amr}
 *     of the session
 * @param userAgent the agent text already sanitized by the request filter, or {@code null}
 * @param clientAddress the resolved address of the client, or {@code null}
 */
public record IssueSessionCommand(StaffAccountId accountId, boolean secondFactorVerified,
        String userAgent, ClientAddress clientAddress) {

    public IssueSessionCommand {
        Objects.requireNonNull(accountId, "accountId");
    }
}
