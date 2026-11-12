package com.confia.guardians.application;

/**
 * The answer to a recorded consent change: the standing of the combination after it and whether an event was written
 * ({@code changed}); repeating the action that already is the current state answers the same standing with {@code
 * changed} false and writes nothing.
 */
public record ConsentChangeView(GuardianConsentsView.Standing standing, boolean changed) {
}
