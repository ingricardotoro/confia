package com.confia.guardians.domain;

import com.confia.kernel.DomainException;
import java.util.Objects;

/**
 * A value of a consent request breaks a rule: the field and a keyword for the rule ({@code required}, {@code invalid},
 * {@code not-consentable}, {@code not-allowed}), never the rejected value. The edge validates the closed sets first, so
 * this is the second line of defense and the one that owns the rules between fields. Published to clients as the
 * Problem Details code {@code validation-failed}.
 */
public final class InvalidConsentException extends DomainException {

    public static final String CODE = "validation-failed";

    public static final String REQUIRED = "required";
    public static final String INVALID = "invalid";
    public static final String NOT_CONSENTABLE = "not-consentable";
    public static final String NOT_ALLOWED = "not-allowed";

    private final String field;
    private final String reason;

    public InvalidConsentException(String field, String reason) {
        super(CODE, "the consent value breaks a domain rule");
        this.field = Objects.requireNonNull(field, "field");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** The member of the request body the rule is about. */
    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}
