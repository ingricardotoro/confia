package com.confia.shared.jobs;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The declaration of a one-time job type (background-jobs-with-db-scheduler design.md decision 6):
 * its name, which is the {@code task_name} of the table, the record that carries its data, and its
 * retry policy. Only types of the JDK appear here, so a business module declares its jobs without
 * importing the scheduling library.
 *
 * <p>The name is module-qualified and lowercase, {@code identity.password-reset-issuance}: the
 * module is the first segment. Whether the payload class obeys {@link JobPayloadRules} is checked
 * when a job is scheduled and by the architecture rules, not by this constructor, so that the rule
 * itself can be tested with types that break it.
 *
 * @param name the task name
 * @param payloadClass the record that is the {@code payload} of the envelope
 * @param retry the bounded retry policy
 * @param <T> the payload type
 */
public record JobType<T extends Record>(String name, Class<T> payloadClass, RetryPolicy retry) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+");

    public JobType {
        requireValidName(name);
        Objects.requireNonNull(payloadClass, "payloadClass");
        Objects.requireNonNull(retry, "retry");
    }

    /**
     * Fails unless {@code name} is a valid task name, for the declarations that carry one.
     *
     * @return the same name
     */
    static String requireValidName(String name) {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "a job name is module-qualified lowercase words, like 'identity.some-job': " + name);
        }
        return name;
    }
}
