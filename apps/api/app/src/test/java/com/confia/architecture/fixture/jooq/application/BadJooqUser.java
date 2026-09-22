package com.confia.architecture.fixture.jooq.application;

import org.jooq.DSLContext;

/**
 * Permanent negative fixture for {@code JooqConfinedToInfrastructureTest} (task 3.1, rule R1):
 * a class that resides outside {@code infrastructure} yet depends on {@code org.jooq}, which
 * ADR-0015 confines to {@code infrastructure} only (design.md decision 9, rule R1).
 */
public final class BadJooqUser {

    private final DSLContext dsl;

    public BadJooqUser(DSLContext dsl) {
        this.dsl = dsl;
    }
}
