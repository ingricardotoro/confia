package com.confia.architecture.fixture.entrypointclient;

import com.confia.architecture.fixture.entrypoints.admin.ScanningEntryPoint;

/**
 * Deliberate violation fixture (ADR-0024): a class outside the entry point root referencing an
 * entry point class, proving the "nothing outside bootstrap references an entry point" rule
 * rejects it.
 */
public class OutsideEntryPointReference {

    public ScanningEntryPoint referencesAnEntryPoint() {
        return new ScanningEntryPoint();
    }
}
