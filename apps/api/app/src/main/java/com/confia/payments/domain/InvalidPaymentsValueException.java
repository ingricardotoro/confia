package com.confia.payments.domain;

import com.confia.kernel.DomainException;

/** A value that breaks a rule of the payments (an amount that is not positive or has sub-cent digits, another currency, a reference or a reason out of its bounds, a date in the future, a cash receipt dated another day than the session's, a field that its method does not take). The edge validates the same rules first, so this is the second line of defense. A rule that is about one field of the request names it, so the client can show it there. Published to clients as the Problem Details code {@code validation-failed}. */
public final class InvalidPaymentsValueException extends DomainException {

    public static final String CODE = "validation-failed";

    private final transient String field;
    private final transient String reason;

    public InvalidPaymentsValueException() {
        this(null, null);
    }

    /**
     * A violation of the rule {@code reason} (kebab case) on the request field {@code field}; both are fixed words, never a
     * rejected value.
     */
    public InvalidPaymentsValueException(String field, String reason) {
        super(CODE, "the payment value breaks a domain rule");
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
