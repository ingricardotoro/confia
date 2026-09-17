package com.confia.architecture.fixture.layering.application;

import com.confia.architecture.fixture.layering.infrastructure.SomeInfrastructureType;
import com.confia.architecture.fixture.layering.web.SomeWebType;

/**
 * Deliberate violation fixture (design.md decision 3, task 3.3, extended for C1): an application
 * type importing both {@code infrastructure} and {@code web} directly, both directions ADR-0002
 * forbids ("application-no-infrastructure"; application never depends on the layer above it
 * either). Permanent, never removed: it is what proves {@link
 * com.confia.architecture.LayeredArchitectureTest} actually rejects both.
 */
public final class BadApplication {

    private final SomeInfrastructureType infrastructureLeakedIntoApplication;
    private final SomeWebType webLeakedIntoApplication;

    public BadApplication(SomeInfrastructureType infrastructureLeakedIntoApplication,
            SomeWebType webLeakedIntoApplication) {
        this.infrastructureLeakedIntoApplication = infrastructureLeakedIntoApplication;
        this.webLeakedIntoApplication = webLeakedIntoApplication;
    }
}
