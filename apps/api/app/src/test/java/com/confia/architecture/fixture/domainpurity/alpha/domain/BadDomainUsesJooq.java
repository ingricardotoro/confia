package com.confia.architecture.fixture.domainpurity.alpha.domain;

import org.jooq.DSLContext;

/**
 * Deliberate violation fixture (build-integrity scenario BI132): a domain class that names a jOOQ
 * type, which the domain purity rule must reject. Permanent.
 */
public final class BadDomainUsesJooq {

    private final DSLContext context;

    public BadDomainUsesJooq(DSLContext context) {
        this.context = context;
    }

    public DSLContext context() {
        return context;
    }
}
