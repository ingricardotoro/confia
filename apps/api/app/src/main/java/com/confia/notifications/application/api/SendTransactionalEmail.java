package com.confia.notifications.application.api;

import com.confia.shared.security.SecurityContext;

/**
 * The only way to send e-mail from this system (transactional-email-adapter design.md decisions 1
 * and 4; scenario NT02). It takes the security context of the caller (the institution and request
 * the delivery log row belongs to), a template of the closed list, one recipient and the template's
 * parameters. It never takes a subject or a body.
 *
 * <p>The send happens outside any database transaction. An accepted send returns a receipt even if
 * the log row could not be written afterwards; a failed one throws {@link
 * EmailDeliveryFailedException} after its row is logged, so the caller decides whether to retry.
 */
public interface SendTransactionalEmail {

    /**
     * @throws EmailDeliveryFailedException if the message was not accepted by the mail server
     */
    EmailDeliveryReceipt send(SecurityContext context, TransactionalEmail email);
}
