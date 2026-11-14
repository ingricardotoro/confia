package com.confia.notifications.application.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A request to send one message: a template of the closed list, one recipient and exactly the
 * parameters that template declares (scenarios NT02 and NT05).
 *
 * <p>The checks run at construction, so an invalid request never reaches the use case, the
 * transport or a connection: the parameter names must be exactly the template's; each value must be
 * non-blank, at most {@value #MAX_PARAMETER_LENGTH} characters, and free of every ISO control
 * character, which rules out header injection through a parameter. A rejection message names the
 * template's own parameter (trusted) and never a caller-supplied name or any value.
 *
 * <p>The parameters can hold a password-reset link: <b>the textual representation is redacted</b>
 * and shows only the template key.
 */
public record TransactionalEmail(TransactionalEmailTemplate template, EmailRecipient recipient,
        Map<String, String> parameters) {

    /** Generous for a URL with a 43-character token, small enough to bound a message. */
    public static final int MAX_PARAMETER_LENGTH = 2048;

    public TransactionalEmail {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(parameters, "parameters");
        parameters = Map.copyOf(parameters);

        Set<String> declared = new TreeSet<>(template.parameterNames());
        for (String name : declared) {
            if (!parameters.containsKey(name)) {
                throw new IllegalArgumentException("missing template parameter: " + name);
            }
        }
        if (parameters.size() != declared.size()) {
            throw new IllegalArgumentException(
                    "the message carries a parameter that its template does not declare");
        }
        for (String name : declared) {
            requireSafeValue(name, parameters.get(name));
        }
    }

    private static void requireSafeValue(String name, String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("template parameter " + name + " must not be blank");
        }
        if (value.length() > MAX_PARAMETER_LENGTH) {
            throw new IllegalArgumentException("template parameter " + name
                    + " must have at most " + MAX_PARAMETER_LENGTH + " characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "template parameter " + name + " must not contain control characters");
        }
    }

    @Override
    public String toString() {
        return "TransactionalEmail[template=" + template.key() + ", recipient=redacted, "
                + "parameters=redacted]";
    }
}
