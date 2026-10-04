package com.confia.architecture.fixture.entrypoints.portal;

import com.confia.architecture.fixture.entrypoints.admin.ScanningEntryPoint;

/**
 * Deliberate violation fixture (ADR-0003, ADR-0024): an entry point sub-package reaching into
 * another one, proving the "entry points do not depend on each other" rule rejects it.
 */
public class CrossEntryPointDependency {

    public ScanningEntryPoint reachesIntoTheAdminEntryPoint() {
        return new ScanningEntryPoint();
    }
}
