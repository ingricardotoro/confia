package com.confia.guardians.domain;

/**
 * The current state of a combination of channel and purpose, derived from its latest event. Nothing is ever
 * pre-marked: a combination without events is {@link #NEVER_GRANTED}, which is not a consent.
 */
public enum ConsentState {
    GRANTED, WITHDRAWN, NEVER_GRANTED
}
