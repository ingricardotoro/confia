package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.util.UUID;

/**
 * Schedules the issuance of a password-reset token for one account (password-recovery-token
 * design.md decision 6). It carries identifiers only, never the address (ADR-0016 rule 3), and is
 * called inside the request's own transaction (ADR-0016 rule 2).
 *
 * <p>The production adapter is {@code JobPasswordResetIssuanceScheduler}
 * (background-jobs-with-db-scheduler design.md decision 18): it schedules a one-time job, so the
 * task exists if and only if the request's transaction commits. The {@code requestId} is the id the
 * request's audit entry carries; with the institution and the account it makes the work unique, so
 * the same request never schedules two issuances.
 */
public interface PasswordResetIssuanceScheduler {

    void schedule(InstitutionId institutionId, StaffAccountId accountId, UUID requestId);
}
