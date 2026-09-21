package com.confia.architecture.fixture.jooq.infrastructure;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;

/**
 * Permanent negative fixture for {@code NoUnapprovedPlainSqlTest} (task 3.4, rule R4): a class
 * that calls {@code DSLContext.fetch(String)}, one of jOOQ's plain-SQL entry points, without
 * appearing in the approved list (design.md decision 9, rule R4). Lives under {@code
 * fixture.jooq.infrastructure} rather than {@code fixture.jooq.application} (task 3.1's fixture
 * package): R4 forbids plain SQL regardless of layer, so this fixture is deliberately placed
 * inside a package that would otherwise be exempt from rule R1.
 */
public final class BadPlainSqlRepository {

    private final DSLContext dsl;

    public BadPlainSqlRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Result<Record> findAll() {
        return dsl.fetch("select * from organization_institution");
    }
}
