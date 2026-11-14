package com.confia.notifications.application;

import com.confia.kernel.InstitutionId;
import com.confia.notifications.application.api.TransactionalEmailTemplate;
import com.confia.notifications.domain.FailureClass;
import com.confia.notifications.domain.RecipientHash;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One send attempt as the delivery log stores it (transactional-email-adapter design.md decision 4,
 * steps 5 and 6, and decision 7). It carries the keyed hash of the recipient, the key and version of the
 * template and the outcome, and nothing from which the address, the subject, the body or a parameter of
 * the message could be recovered.
 *
 * <p>The canonical constructor mirrors the {@code notifications_email_delivery_outcome_chk} constraint,
 * so an incoherent attempt is refused here, with a message that names no value, instead of surfacing as
 * a database error later: a {@link Status#SENT} attempt has no failure class, and a {@link Status#FAILED}
 * attempt has one and no provider identifier. Use {@link #sent} and {@link #failed}.
 *
 * @param providerMessageId the identifier the mail provider gave the accepted message, or {@code null}
 *     when it gave none; always {@code null} for a failed attempt
 * @param failureClass the closed reason of a failed attempt, or {@code null} for a sent one
 */
public record EmailDeliveryAttempt(InstitutionId institutionId, UUID id, UUID requestId,
        Instant attemptedAt, TransactionalEmailTemplate template, RecipientHash recipient,
        Status status, String providerMessageId, FailureClass failureClass) {

    /** The two outcomes the log accepts. */
    public enum Status {
        /** The mail server accepted the message. */
        SENT,
        /** The message was not accepted. */
        FAILED
    }

    public EmailDeliveryAttempt {
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(attemptedAt, "attemptedAt");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(status, "status");
        if (providerMessageId != null && providerMessageId.isBlank()) {
            throw new IllegalArgumentException("a provider message identifier must not be blank");
        }
        boolean coherent = status == Status.SENT
                ? failureClass == null
                : failureClass != null && providerMessageId == null;
        if (!coherent) {
            throw new IllegalArgumentException("the attempt is incoherent with its status");
        }
    }

    /** An attempt the mail server accepted; {@code providerMessageId} may be {@code null}. */
    public static EmailDeliveryAttempt sent(InstitutionId institutionId, UUID id, UUID requestId,
            Instant attemptedAt, TransactionalEmailTemplate template, RecipientHash recipient,
            String providerMessageId) {
        return new EmailDeliveryAttempt(institutionId, id, requestId, attemptedAt, template,
                recipient, Status.SENT, providerMessageId, null);
    }

    /** An attempt that failed for {@code failureClass}. */
    public static EmailDeliveryAttempt failed(InstitutionId institutionId, UUID id, UUID requestId,
            Instant attemptedAt, TransactionalEmailTemplate template, RecipientHash recipient,
            FailureClass failureClass) {
        Objects.requireNonNull(failureClass, "failureClass");
        return new EmailDeliveryAttempt(institutionId, id, requestId, attemptedAt, template,
                recipient, Status.FAILED, null, failureClass);
    }
}
