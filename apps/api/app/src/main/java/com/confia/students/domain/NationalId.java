package com.confia.students.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A national identity document in transit: it exists in memory only between the request and the moment
 * it is encrypted. It is 8 to 20 letters, digits or hyphens; its normal form is uppercase without
 * hyphens, so 0801-1999-12345 and 0801199912345 are the same document. Once normalised it must keep at
 * least five characters. No part of it is ever shown, stored in plain or audited.
 *
 * <p>{@link #toString()} and every failure are redacted: the document is personal data of a minor and
 * must never reach a log, an audit entry or a message.
 */
public final class NationalId {

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9-]{8,20}");
    private static final int MIN_NORMALIZED_LENGTH = 5;

    private final String normalized;

    private NationalId(String normalized) {
        this.normalized = normalized;
    }

    /** The document as typed; stripped of border spaces first. */
    public static NationalId of(String raw) {
        if (raw == null) {
            throw new InvalidStudentException();
        }
        String stripped = raw.strip();
        if (!SHAPE.matcher(stripped).matches()) {
            throw new InvalidStudentException();
        }
        String normalized = stripped.replace("-", "").toUpperCase(Locale.ROOT);
        if (normalized.length() < MIN_NORMALIZED_LENGTH) {
            throw new InvalidStudentException();
        }
        return new NationalId(normalized);
    }

    /** Uppercase, without hyphens: the value that is encrypted and indexed. */
    public String normalized() {
        return normalized;
    }

    @Override
    public String toString() {
        return "NationalId[REDACTED]";
    }
}
