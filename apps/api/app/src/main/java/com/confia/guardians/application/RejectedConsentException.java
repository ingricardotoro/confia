package com.confia.guardians.application;

import com.confia.kernel.DomainException;
import java.util.Objects;

/**
 * A value of a consent request breaks a rule of the domain, named for the edge: the field and a keyword for the rule
 * ({@code required}, {@code invalid}, {@code not-consentable}, {@code not-allowed}), never the value. The use case
 * raises it from the domain's own error so the web layer, which cannot see the domain, answers {@code
 * validation-failed} with the field in {@code errors}.
 */
public final class RejectedConsentException extends DomainException {

    public static final String CODE = "validation-failed";

    private final String field;
    private final String reason;

    public RejectedConsentException(String field, String reason) {
        super(CODE, "the consent request breaks a rule");
        this.field = Objects.requireNonNull(field, "field");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}
