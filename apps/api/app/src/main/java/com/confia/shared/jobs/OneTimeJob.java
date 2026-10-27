package com.confia.shared.jobs;

import java.util.Objects;

/**
 * A one-time job type with the handler that runs it.
 *
 * @param type the declaration of the type
 * @param handler runs one execution
 * @param <T> the payload type
 */
public record OneTimeJob<T extends Record>(JobType<T> type, JobHandler<T> handler) implements JobDefinition {

    public OneTimeJob {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
    }

    @Override
    public String name() {
        return type.name();
    }
}
