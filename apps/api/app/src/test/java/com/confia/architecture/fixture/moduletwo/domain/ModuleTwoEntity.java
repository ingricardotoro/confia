package com.confia.architecture.fixture.moduletwo.domain;

/**
 * Deliberate violation fixture (design.md decision 3, task 3.3). A plain domain type of a
 * simulated second business module, imported directly by {@code moduleone}'s domain to prove the
 * "no cross-module domain import" ArchUnit rule rejects it (task 3.4).
 */
public final class ModuleTwoEntity {

    private final String id;

    public ModuleTwoEntity(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
