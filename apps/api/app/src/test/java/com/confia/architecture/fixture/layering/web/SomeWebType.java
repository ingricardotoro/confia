package com.confia.architecture.fixture.layering.web;

/**
 * Deliberate violation fixture (design.md decision 3, task 3.3, extended for C1): a plain web
 * type, imported directly by {@code BadApplication} to prove {@link
 * com.confia.architecture.LayeredArchitectureTest} rejects {@code application} reaching sideways
 * into {@code web}.
 */
public final class SomeWebType {

    public String renderedForTheClient() {
        return "not domain or application logic";
    }
}
