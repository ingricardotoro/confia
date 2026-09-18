package com.confia.architecture.fixture.cycle.packageb;

import com.confia.architecture.fixture.cycle.packagea.ClassA;

/**
 * Deliberate violation fixture (design.md decision 3, task 3.3): half of a permanent two-package
 * cycle with {@link ClassA}, proving the "no cycles" ArchUnit rule (task 3.4) actually rejects a
 * cyclic dependency between packages.
 */
public final class ClassB {

    public ClassA dependsOnPackageA() {
        return new ClassA();
    }
}
