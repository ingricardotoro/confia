package com.confia.architecture.fixture.layering.infrastructure;

/**
 * Deliberate violation fixture (design.md decision 3, task 3.3): a plain infrastructure type,
 * imported directly by {@link com.confia.architecture.fixture.layering.domain.BadDomain} to prove
 * the "domain does not depend on outer layers" ArchUnit rule rejects it (task 3.4).
 */
public final class SomeInfrastructureType {

    public String readFromTheOutsideWorld() {
        return "not domain logic";
    }
}
