package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/**
 * A value that breaks a rule of the scholarships (a percentage out of its bounds, an amount that is not positive or has sub-cent
 * digits, another currency, a code, a name or a reason out of its bounds, a validity that ends before it starts or outside its
 * academic year). The edge validates the same rules first, so this is the second line of defense. A rule that is about one field of
 * the request names it, so the client can show it there. Published to clients as the Problem Details code {@code validation-failed}.
 */
public final class InvalidScholarshipsValueException extends DomainException {

    public static final String CODE = "validation-failed";

    private final transient String field;
    private final transient String reason;

    public InvalidScholarshipsValueException() {
        this(null, null);
    }

    /** A violation of the rule {@code reason} (kebab case) on the request field {@code field}; both are fixed words, never a rejected value. */
    public InvalidScholarshipsValueException(String field, String reason) {
        super(CODE, "the scholarship value breaks a domain rule");
        this.field = field;
        this.reason = reason;
    }

    /** The request field the rule is about, or {@code null} when it is about the request as a whole. */
    public String field() {
        return field;
    }

    /** The rule that was broken, or {@code null} when no field is named. */
    public String reason() {
        return reason;
    }
}
