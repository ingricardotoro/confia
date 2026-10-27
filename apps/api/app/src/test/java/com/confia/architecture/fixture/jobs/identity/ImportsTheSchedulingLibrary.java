package com.confia.architecture.fixture.jobs.identity;

import com.github.kagkarlsson.scheduler.Scheduler;

/** Fixture: a class outside the jobs infrastructure that imports the scheduling library. */
public class ImportsTheSchedulingLibrary {

    public Class<?> type() {
        return Scheduler.class;
    }
}
