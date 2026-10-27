package com.confia.architecture.fixture.jobs.payload;

import com.confia.shared.jobs.JobType;
import com.confia.shared.jobs.RetryPolicy;
import java.time.Duration;

/** Fixture: a registered job type whose payload declares a {@code String email}. */
public class RegistersATextPayload {

    public record TextPayload(String email) {
    }

    public static final JobType<TextPayload> TYPE = new JobType<>("identity.text-payload", TextPayload.class,
            new RetryPolicy(1, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(1)));
}
