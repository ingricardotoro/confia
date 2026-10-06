package com.confia.architecture.fixture.sharedboundary.identity;

/**
 * Deliberate violation fixture (web-edge-foundations design.md, decision 20, rule W3): a plain type
 * of a business module, imported by {@code BadSharedUsesIdentity} to prove that {@code
 * SharedBoundaryRulesTest} rejects {@code shared} reaching into a module. Permanent, never removed.
 */
public final class SomeIdentityType {

    public String ownedByTheModule() {
        return "not shared";
    }
}
