package com.confia.architecture.fixture.scheduling;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Fixture: a type of {@code org.springframework.scheduling}. */
public class UsesSpringSchedulingType {

    public Object scheduler() {
        return new ThreadPoolTaskScheduler();
    }
}
