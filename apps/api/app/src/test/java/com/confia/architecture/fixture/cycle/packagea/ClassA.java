package com.confia.architecture.fixture.cycle.packagea;

import com.confia.architecture.fixture.cycle.packageb.ClassB;

/**
 * Deliberate violation fixture (design.md decision 3, task 3.3): half of a permanent two-package
 * cycle with {@link ClassB}, proving the "no cycles" ArchUnit rule (task 3.4) actually rejects a
 * cyclic dependency between packages.
 */
public final class ClassA {

    public ClassB dependsOnPackageB() {
        return new ClassB();
    }
}
