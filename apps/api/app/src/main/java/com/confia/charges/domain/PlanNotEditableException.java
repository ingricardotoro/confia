package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * An edit that the state of the plan does not allow: items added or removed once it is not a draft, a change of the
 * frequency or of the due day once it is active, any change of an archived plan, or a transition that is not draft to
 * active and active to archived. Published to clients as the Problem Details code {@code plan-not-editable}.
 */
public final class PlanNotEditableException extends DomainException {

    public static final String CODE = "plan-not-editable";

    public PlanNotEditableException() {
        super(CODE, "the state of the charge plan does not allow this change");
    }
}
