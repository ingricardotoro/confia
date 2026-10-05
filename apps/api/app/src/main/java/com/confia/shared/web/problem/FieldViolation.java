package com.confia.shared.web.problem;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One entry of the {@code errors} member of a {@code validation-failed} response: the field and
 * the constraint it broke, never the rejected value (web-edge-foundations design.md, decision 9).
 * {@code errors} is an RFC 9457 extension member that the {@code ProblemDetail} schema of the
 * OpenAPI contract does not declare yet.
 *
 * @param field the path of the field in the request, for example {@code items[0].quantity}
 * @param reason the constraint, as the simple name of its annotation in kebab case ({@code
 *     not-blank}, {@code size}, {@code pattern}), stable for the interface's message map
 */
public record FieldViolation(String field, String reason) {

    private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    /** A violation of the constraint named {@code constraintName}, such as {@code NotBlank}. */
    public static FieldViolation of(String field, String constraintName) {
        return new FieldViolation(field,
                WORD_BOUNDARY.matcher(constraintName).replaceAll("-").toLowerCase(Locale.ROOT));
    }
}
