package com.confia.architecture.fixture.scheduling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Fixture: {@code ScheduledExecutorService}. */
public class UsesScheduledExecutorService {

    public ScheduledExecutorService executor() {
        return Executors.newScheduledThreadPool(1);
    }
}
