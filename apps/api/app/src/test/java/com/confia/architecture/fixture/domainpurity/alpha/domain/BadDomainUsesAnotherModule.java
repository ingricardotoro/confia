package com.confia.architecture.fixture.domainpurity.alpha.domain;

import com.confia.architecture.fixture.domainpurity.other.SomeOtherModuleType;

/**
 * Deliberate violation fixture (build-integrity scenario BI132): a domain class that depends on a
 * type that is neither the kernel nor its own module, which the domain purity rule must reject.
 * Permanent.
 */
public final class BadDomainUsesAnotherModule {

    private final SomeOtherModuleType other;

    public BadDomainUsesAnotherModule(SomeOtherModuleType other) {
        this.other = other;
    }

    public SomeOtherModuleType other() {
        return other;
    }
}
