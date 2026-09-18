package com.confia.architecture.fixture.moduleone.domain;

import com.confia.architecture.fixture.moduletwo.domain.ModuleTwoEntity;

/**
 * Deliberate violation fixture (design.md decision 3, task 3.3): a simulated business module's
 * domain importing another simulated module's domain directly, instead of going through a public
 * use case or a domain event. Permanent, never removed: it is what proves the "no cross-module
 * domain import" ArchUnit rule (task 3.4) actually rejects something, today and after every future
 * refactor.
 */
public final class ModuleOneEntity {

    private final ModuleTwoEntity linkedEntityFromAnotherModule;

    public ModuleOneEntity(ModuleTwoEntity linkedEntityFromAnotherModule) {
        this.linkedEntityFromAnotherModule = linkedEntityFromAnotherModule;
    }

    public ModuleTwoEntity linkedEntityFromAnotherModule() {
        return linkedEntityFromAnotherModule;
    }
}
