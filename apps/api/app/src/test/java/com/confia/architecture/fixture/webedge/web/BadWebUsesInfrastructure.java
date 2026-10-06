package com.confia.architecture.fixture.webedge.web;

import com.confia.architecture.fixture.layering.infrastructure.SomeInfrastructureType;
import confia.generated.jooq.Public;
import org.jooq.DSLContext;

/**
 * Deliberate violation fixture (web-edge-foundations design.md, decision 20, rule W1): a class of a
 * {@code web} package that depends on an {@code infrastructure} type, on {@code org.jooq} and on
 * the generated jOOQ package, the three targets {@code WebLayerDependencyRulesTest} forbids.
 * Permanent, never removed: it is what proves that rule rejects something (ADR-0018).
 */
public final class BadWebUsesInfrastructure {

    private final SomeInfrastructureType infrastructureLeakedIntoWeb;
    private final DSLContext jooqLeakedIntoWeb;

    public BadWebUsesInfrastructure(SomeInfrastructureType infrastructureLeakedIntoWeb,
            DSLContext jooqLeakedIntoWeb) {
        this.infrastructureLeakedIntoWeb = infrastructureLeakedIntoWeb;
        this.jooqLeakedIntoWeb = jooqLeakedIntoWeb;
    }

    public Public generatedSchemaLeakedIntoWeb() {
        return Public.PUBLIC;
    }
}
