package com.confia.architecture.fixture.scheduling;

import org.springframework.scheduling.annotation.Async;

/** Fixture: {@code @Async}. */
public class UsesAsyncAnnotation {

    @Async
    public void run() {
    }
}
