package com.confia.shared.jobs.infrastructure;

import com.confia.shared.jobs.TracedJob;
import com.github.kagkarlsson.scheduler.boot.autoconfigure.Jackson3Serializer;
import com.github.kagkarlsson.scheduler.exceptions.SerializationException;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only serializer of {@code task_data}, shared by the {@code Scheduler} of the worker and by the
 * scheduling client of the administrative and portal processes (background-jobs-with-db-scheduler
 * design.md decision 8; ADR-0016 compliance 6). It wraps the library's {@link Jackson3Serializer}
 * (probe P-4) around a strict {@link JsonMapper} and adds what the library does not.
 *
 * <ul>
 *   <li><b>Only the envelope.</b> It writes and reads {@link TracedJob} and nothing else, as one JSON
 *       object {@code {"payload":{…},"meta":{…}}} in UTF-8. The library's default, Java object
 *       serialization, is never used: any role with {@code INSERT} on the table, the portal's
 *       included, could plant an object. A document that does not start with {@code '{'}, which
 *       includes the {@code 0xACED} header of an {@code ObjectOutputStream}, is refused before any
 *       parser or class loader sees it.
 *   <li><b>Strict.</b> Unknown properties, {@code null} for a primitive and trailing tokens are
 *       errors; there is no polymorphic typing; the parser does not include the source in error
 *       locations; dates are ISO-8601 text.
 *   <li><b>At most {@value #MAX_DOCUMENT_BYTES} bytes</b>, on writing and on reading.
 *   <li><b>No data in errors.</b> Every failure becomes a {@link JobDataRejectedException} that names
 *       the task and the class of the original exception and chains nothing.
 * </ul>
 *
 * <p>The payload of a document read through the library is a generic map. {@link #payloadOf} turns it
 * into the record of its job type with the same strict mapper, which is the one conversion in the
 * system and the one that rejects an unknown field of the payload.
 */
public final class TracedJobSerializer implements Serializer {

    /** The longest document, in bytes. */
    public static final int MAX_DOCUMENT_BYTES = 4096;

    /** The task name used when the library calls the serializer, which does not tell it the task. */
    public static final String UNRESOLVED_TASK = "(unresolved)";

    private static final byte OPEN_BRACE = '{';

    private final JsonMapper mapper;
    private final Serializer delegate;

    public TracedJobSerializer() {
        this.mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        this.delegate = new Jackson3Serializer(mapper);
    }

    /** Writes {@code job} as the document of {@code taskName}; refuses one over the size limit. */
    public byte[] serialize(String taskName, TracedJob<?> job) {
        Objects.requireNonNull(job, "job");
        byte[] document;
        try {
            document = delegate.serialize(job);
        } catch (RuntimeException e) {
            throw JobDataRejectedException.because(taskName, originalOf(e));
        }
        if (document.length > MAX_DOCUMENT_BYTES) {
            throw JobDataRejectedException.because(taskName, JobDataRejectedException.DOCUMENT_TOO_LARGE);
        }
        return document;
    }

    /**
     * Reads the document of {@code taskName}. The payload comes back as a generic map; convert it with
     * {@link #payloadOf}.
     */
    public TracedJob<?> deserialize(String taskName, byte[] document) {
        if (document == null || document.length == 0 || document[0] != OPEN_BRACE) {
            throw JobDataRejectedException.because(taskName, JobDataRejectedException.NOT_A_JSON_OBJECT);
        }
        if (document.length > MAX_DOCUMENT_BYTES) {
            throw JobDataRejectedException.because(taskName, JobDataRejectedException.DOCUMENT_TOO_LARGE);
        }
        try {
            return delegate.deserialize(TracedJob.class, document);
        } catch (RuntimeException e) {
            throw JobDataRejectedException.because(taskName, originalOf(e));
        }
    }

    /**
     * The payload of {@code job} as {@code payloadClass}, rejecting unknown fields and {@code null}
     * for a primitive.
     */
    public <P extends Record> P payloadOf(String taskName, Class<P> payloadClass, TracedJob<?> job) {
        Objects.requireNonNull(payloadClass, "payloadClass");
        Object payload = Objects.requireNonNull(job, "job").payload();
        if (payloadClass.isInstance(payload)) {
            return payloadClass.cast(payload);
        }
        if (!(payload instanceof Map<?, ?>)) {
            throw JobDataRejectedException.because(taskName, JobDataRejectedException.NOT_A_JSON_OBJECT);
        }
        try {
            return mapper.convertValue(payload, payloadClass);
        } catch (RuntimeException e) {
            throw JobDataRejectedException.because(taskName, originalOf(e));
        }
    }

    /** The library's entry point: it does not know the task, so the error cannot name it. */
    @Override
    public byte[] serialize(Object data) {
        if (!(data instanceof TracedJob<?> job)) {
            throw JobDataRejectedException.because(UNRESOLVED_TASK, JobDataRejectedException.UNSUPPORTED_TYPE);
        }
        return serialize(UNRESOLVED_TASK, job);
    }

    /** The library's entry point: it does not know the task, so the error cannot name it. */
    @Override
    public <T> T deserialize(Class<T> clazz, byte[] serializedData) {
        if (clazz != TracedJob.class) {
            throw JobDataRejectedException.because(UNRESOLVED_TASK, JobDataRejectedException.UNSUPPORTED_TYPE);
        }
        return clazz.cast(deserialize(UNRESOLVED_TASK, serializedData));
    }

    /** The exception behind the library's wrapper, which only adds a fixed message of its own. */
    private static Throwable originalOf(RuntimeException e) {
        return e instanceof SerializationException && e.getCause() != null ? e.getCause() : e;
    }
}
