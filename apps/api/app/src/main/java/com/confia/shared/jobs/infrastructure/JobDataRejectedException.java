package com.confia.shared.jobs.infrastructure;

import java.util.Objects;

/**
 * Task data that was refused, when it was written or when it was read
 * (background-jobs-with-db-scheduler design.md decision 8; scenarios BJ11, BJ53, BJ54 and BJ55).
 *
 * <p>The message names the task and the class of the exception that caused the refusal, or a fixed
 * reason when there was none. It never carries the original message and never chains the original
 * exception: the message of a JSON parser can quote a field name or a fragment of the data, and the
 * data of a task must not reach a log (CLAUDE.md regla 11). For the same reason this class passes no
 * cause to {@code super} and nothing is ever added as suppressed.
 */
public final class JobDataRejectedException extends RuntimeException {

    /** The document is not a single JSON object. */
    public static final String NOT_A_JSON_OBJECT = "the document is not a single JSON object";

    /** The document is longer than {@link TracedJobSerializer#MAX_DOCUMENT_BYTES}. */
    public static final String DOCUMENT_TOO_LARGE = "the document exceeds "
            + TracedJobSerializer.MAX_DOCUMENT_BYTES + " bytes";

    /** Only the task envelope is written or read; anything else, Java serialization included, is not. */
    public static final String UNSUPPORTED_TYPE = "only the task envelope is serialized";

    private static final long serialVersionUID = 1L;

    private final String taskName;

    private JobDataRejectedException(String taskName, String detail) {
        super("task data rejected for task '" + taskName + "': " + detail);
        this.taskName = taskName;
    }

    /** A refusal caused by {@code original}: only its class is kept. */
    static JobDataRejectedException because(String taskName, Throwable original) {
        Objects.requireNonNull(original, "original");
        return new JobDataRejectedException(taskName, original.getClass().getName());
    }

    /** A refusal that has no original exception, with one of the fixed reasons of this class. */
    static JobDataRejectedException because(String taskName, String reason) {
        return new JobDataRejectedException(taskName, reason);
    }

    /** The task the data belongs to, or {@link TracedJobSerializer#UNRESOLVED_TASK} when unknown. */
    public String taskName() {
        return taskName;
    }
}
