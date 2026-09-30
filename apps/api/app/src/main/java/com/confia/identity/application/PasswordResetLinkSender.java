package com.confia.identity.application;

import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;

/**
 * Delivers the reset link for a freshly issued token (password-recovery-token design.md decision
 * 6). It is the only place the clear-text token leaves the module, and it is called only after the
 * issuing transaction has committed, never while the account lock is held (ADR-0016 rule 7).
 *
 * <p><b>No production adapter in this change</b>: it belongs to {@code transactional-email-adapter},
 * change 14 of F0. A failed delivery leaves an issued, undelivered token; the holder repeats the
 * request and the new issuance supersedes it. Retrying or queueing delivery is change 14's contract.
 */
public interface PasswordResetLinkSender {

    void send(InstitutionId institutionId, StaffAccountId accountId, PlainPasswordResetToken token);
}
