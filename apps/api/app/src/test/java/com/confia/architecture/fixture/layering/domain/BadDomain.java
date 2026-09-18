package com.confia.architecture.fixture.layering.domain;

import com.confia.architecture.fixture.layering.infrastructure.SomeInfrastructureType;

/**
 * Deliberate violation fixture (design.md decision 3, task 3.3): a domain type importing
 * {@code infrastructure} directly, inverting the only direction ADR-0002 allows. Permanent, never
 * removed: it is what proves the layering ArchUnit rule (task 3.4) actually rejects something.
 */
public final class BadDomain {

    private final SomeInfrastructureType infrastructureLeakedIntoDomain;

    public BadDomain(SomeInfrastructureType infrastructureLeakedIntoDomain) {
        this.infrastructureLeakedIntoDomain = infrastructureLeakedIntoDomain;
    }

    public SomeInfrastructureType infrastructureLeakedIntoDomain() {
        return infrastructureLeakedIntoDomain;
    }
}
