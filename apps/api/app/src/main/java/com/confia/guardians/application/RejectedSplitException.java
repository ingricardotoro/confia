package com.confia.guardians.application;

import com.confia.kernel.DomainException;
import java.util.Objects;

/**
 * The set of links of a student breaks INV-32 or its companion rules, named for the edge: the field and the
 * keyword of the rule ({@code sum-not-100}, {@code multiple-primary-payer}, {@code multiple-billing-recipient},
 * {@code duplicate-guardian}), never a value. The use case raises it from the domain's own error so the web layer,
 * which cannot see the domain, answers {@code invalid-responsibility-split} with the list of violations. Without
 * the edge's help it is still a plain {@code 400 invalid-responsibility-split}.
 */
public final class RejectedSplitException extends DomainException {

    public static final String CODE = "invalid-responsibility-split";

    /** The member of the request body the rule is about. */
    public static final String FIELD = "links";

    private final String reason;

    public RejectedSplitException(String reason) {
        super(CODE, "the set of guardian links breaks a rule");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String field() {
        return FIELD;
    }

    public String reason() {
        return reason;
    }
}
