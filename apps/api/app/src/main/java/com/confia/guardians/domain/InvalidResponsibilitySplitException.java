package com.confia.guardians.domain;

import com.confia.kernel.DomainException;
import java.util.Objects;

/**
 * The set of links of a student breaks INV-32 or the rules that go with it. {@link #reason()} is one of
 * {@code sum-not-100}, {@code multiple-primary-payer}, {@code multiple-billing-recipient} and
 * {@code duplicate-guardian}: a fixed keyword, never a value of the request. Published to clients as the
 * Problem Details code {@code invalid-responsibility-split}.
 */
public final class InvalidResponsibilitySplitException extends DomainException {

    public static final String CODE = "invalid-responsibility-split";

    public static final String SUM_NOT_100 = "sum-not-100";
    public static final String MULTIPLE_PRIMARY_PAYER = "multiple-primary-payer";
    public static final String MULTIPLE_BILLING_RECIPIENT = "multiple-billing-recipient";
    public static final String DUPLICATE_GUARDIAN = "duplicate-guardian";

    private final String reason;

    public InvalidResponsibilitySplitException(String reason) {
        super(CODE, "the set of guardian links of the student breaks a rule: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
