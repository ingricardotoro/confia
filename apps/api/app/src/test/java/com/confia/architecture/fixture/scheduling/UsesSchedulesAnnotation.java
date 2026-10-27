package com.confia.architecture.fixture.scheduling;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

/** Fixture: the repeating container annotation {@code @Schedules}. */
public class UsesSchedulesAnnotation {

    @Schedules({@Scheduled(fixedRate = 1000)})
    public void tick() {
    }
}
