package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/** An edit that names a version other than the current one. Published as {@code stale-version}. */
public final class StaleAccountVersionException extends DomainException {

    public static final String CODE = "stale-version";

    public StaleAccountVersionException() {
        super(CODE, "the version of the row is not the current one");
    }
}
