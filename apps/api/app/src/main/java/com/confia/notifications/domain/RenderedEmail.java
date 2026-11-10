package com.confia.notifications.domain;

import java.util.Objects;

/**
 * A composed plain-text message: a one-line subject and a body, both taken from the {@code es-HN}
 * catalog with the template parameters substituted (design.md decision 6; scenario NT03).
 *
 * <p>The subject becomes a mail header, so it carries no control character at all, which rules out
 * header injection by construction (NT05). The body may span lines, so line breaks and tabs are
 * allowed there and every other control character is not. The body can hold a password-reset link:
 * <b>the textual representation is redacted</b> and the value is never written to a log or an
 * exception message.
 */
public record RenderedEmail(String subject, String body) {

    /** Far below the 998 characters of RFC 5322, so a subject never needs header folding. */
    public static final int MAX_SUBJECT_LENGTH = 200;

    public RenderedEmail {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
        if (subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH
                || subject.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("the subject must be one non-blank line of at most "
                    + MAX_SUBJECT_LENGTH + " characters without control characters");
        }
        if (body.isBlank() || body.chars().anyMatch(RenderedEmail::isForbiddenInBody)) {
            throw new IllegalArgumentException(
                    "the body must be non-blank and free of control characters other than line breaks and tabs");
        }
    }

    private static boolean isForbiddenInBody(int character) {
        return Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t';
    }

    @Override
    public String toString() {
        return "RenderedEmail[redacted]";
    }
}
