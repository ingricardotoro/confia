package com.confia.architecture.fixture.scheduling;

import java.util.Timer;

/** Fixture: {@code java.util.Timer}. */
public class UsesTimer {

    public Timer timer() {
        return new Timer(true);
    }
}
