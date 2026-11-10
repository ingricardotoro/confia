package com.confia.notifications.application.api;

import java.util.regex.Pattern;

/**
 * The outcome of an accepted send: the mail server answered success at the end of the SMTP
 * transaction (specs/notifications/spec.md, conventions). {@code providerMessageId} is the
 * identifier the provider reported for the message, when it reported one (Amazon SES does, in its
 * last {@code 250} reply), and {@code null} otherwise. It is restricted to a safe alphabet and 255
 * characters before it is kept, because it comes from a remote server.
 */
public record EmailDeliveryReceipt(String providerMessageId) {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._@<>-]{1,255}");

    public EmailDeliveryReceipt {
        if (providerMessageId != null && !SAFE_ID.matcher(providerMessageId).matches()) {
            throw new IllegalArgumentException(
                    "a provider message id must be 1 to 255 characters of [A-Za-z0-9._@<>-]");
        }
    }
}
