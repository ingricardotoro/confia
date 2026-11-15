package com.confia.notifications.application.api;

import com.confia.notifications.domain.FailureClass;
import java.util.Objects;

/**
 * The failure of a send, as the caller sees it (scenario NT15): it carries the closed {@link
 * FailureClass} and nothing else. It has no cause and its message is built from the class code
 * alone, because the message, the cause and the reply of the mail server or of the mail library can
 * contain the recipient's address.
 */
public final class EmailDeliveryFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureClass failureClass;

    public EmailDeliveryFailedException(FailureClass failureClass) {
        super("email delivery failed: " + Objects.requireNonNull(failureClass, "failureClass").code());
        this.failureClass = failureClass;
    }

    public FailureClass failureClass() {
        return failureClass;
    }
}
