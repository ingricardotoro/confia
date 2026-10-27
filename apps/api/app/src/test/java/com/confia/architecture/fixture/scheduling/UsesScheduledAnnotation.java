package com.confia.architecture.fixture.scheduling;

import org.springframework.scheduling.annotation.Scheduled;

/** Fixture: a production-style class that schedules in memory with {@code @Scheduled}. */
public class UsesScheduledAnnotation {

    @Scheduled(fixedRate = 1000)
    public void tick() {
    }
}
