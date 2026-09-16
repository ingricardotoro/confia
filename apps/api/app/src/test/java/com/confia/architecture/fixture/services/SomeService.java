package com.confia.architecture.fixture.services;

/**
 * Deliberate violation fixture (design.md decision 3, task 3.3): a package literally named
 * {@code services}, one of the banned technical-layer names. Permanent, never removed: it is what
 * proves the banned-package-name ArchUnit rule (task 3.4) actually rejects it.
 */
public final class SomeService {
}
