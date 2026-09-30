package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;

/**
 * Schedules the issuance of a password-reset token for one account (password-recovery-token
 * design.md decision 6). It carries identifiers only, never the address (ADR-0016 rule 3), and is
 * called inside the request's own transaction (ADR-0016 rule 2).
 *
 * <p><b>No production adapter in this change</b>: the db-scheduler adapter arrives with change 9,
 * {@code background-jobs-with-db-scheduler}. Until then no issuance runs by itself.
 */
public interface PasswordResetIssuanceScheduler {

    void schedule(InstitutionId institutionId, StaffAccountId accountId);
}
