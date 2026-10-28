package com.confia.identity.infrastructure;

import com.confia.identity.application.PasswordResetDeliveryUnavailableException;
import com.confia.identity.application.PasswordResetLinkSender;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;

/**
 * The only production implementation of the link-sender port while no email adapter exists
 * (background-jobs-with-db-scheduler design.md decision 18, owner decision P4): it fails explicitly
 * and never delivers, nor pretends to. The failure is raised in {@link #ensureAvailable()}, which
 * the issuance calls before it opens its transaction, so an attempt fails without issuing a token;
 * it is raised in {@link #send} as well, in case that method is called directly. Its replacement is
 * {@code transactional-email-adapter}, change 14 of F0.
 *
 * <p>The exception has a fixed message: the token, the link and the address never reach it.
 */
public final class UnavailablePasswordResetLinkSender implements PasswordResetLinkSender {

    @Override
    public void ensureAvailable() {
        throw new PasswordResetDeliveryUnavailableException();
    }

    @Override
    public void send(InstitutionId institutionId, StaffAccountId accountId,
            PlainPasswordResetToken token) {
        throw new PasswordResetDeliveryUnavailableException();
    }
}
