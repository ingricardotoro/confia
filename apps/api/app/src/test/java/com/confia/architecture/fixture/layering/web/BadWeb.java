package com.confia.architecture.fixture.layering.web;

import com.confia.architecture.fixture.layering.infrastructure.SomeInfrastructureType;

/**
 * Deliberate violation fixture (design.md decision 3, task 3.3, extended for C1): a web type
 * reaching into infrastructure directly, skipping application. ADR-0002's "interface-only-
 * application" demands web depend on nothing but application. Permanent, never removed.
 */
public final class BadWeb {

    private final SomeInfrastructureType infrastructureLeakedIntoWeb;

    public BadWeb(SomeInfrastructureType infrastructureLeakedIntoWeb) {
        this.infrastructureLeakedIntoWeb = infrastructureLeakedIntoWeb;
    }
}
