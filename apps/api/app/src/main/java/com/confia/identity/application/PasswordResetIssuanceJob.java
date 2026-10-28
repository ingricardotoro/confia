package com.confia.identity.application;

import java.util.Objects;
import java.util.UUID;

/**
 * The payload of the password-reset issuance job (background-jobs-with-db-scheduler design.md
 * decision 18): the internal identifier of the account and nothing else. The institution travels in
 * the job's metadata, and the address never travels at all (ADR-0016 rule 3).
 *
 * @param accountId the internal identifier of the account to issue a token for
 */
public record PasswordResetIssuanceJob(UUID accountId) {

    public PasswordResetIssuanceJob {
        Objects.requireNonNull(accountId, "accountId");
    }
}
