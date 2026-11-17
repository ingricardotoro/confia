package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * An edit that names a version other than the current one: somebody else changed the plan after the editor read it.
 * Published to clients as the Problem Details code {@code stale-version}.
 */
public final class StaleVersionException extends DomainException {

    public static final String CODE = "stale-version";

    public StaleVersionException() {
        super(CODE, "the version of the row is not the current one");
    }
}
