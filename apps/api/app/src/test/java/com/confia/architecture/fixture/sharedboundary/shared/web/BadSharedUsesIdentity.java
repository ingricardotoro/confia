package com.confia.architecture.fixture.sharedboundary.shared.web;

import com.confia.architecture.fixture.sharedboundary.identity.SomeIdentityType;

/**
 * Deliberate violation fixture (web-edge-foundations design.md, decision 20, rule W3): a class of
 * {@code shared.web} that depends on a type of a business module, inverting the only direction
 * the architecture allows ({@code identity} depends on {@code shared}, never the reverse).
 * Permanent, never removed.
 */
public final class BadSharedUsesIdentity {

    private final SomeIdentityType moduleTypeLeakedIntoShared;

    public BadSharedUsesIdentity(SomeIdentityType moduleTypeLeakedIntoShared) {
        this.moduleTypeLeakedIntoShared = moduleTypeLeakedIntoShared;
    }
}
