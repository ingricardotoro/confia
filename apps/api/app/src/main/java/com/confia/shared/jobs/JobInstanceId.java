package com.confia.shared.jobs;

import com.confia.shared.security.Digests;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The identifier of one scheduled execution, the {@code task_instance} of the table
 * (background-jobs-with-db-scheduler design.md decision 6): 32 lowercase hexadecimal characters,
 * derived from non-personal identifiers, so scheduling the same work twice finds the same instance
 * and the library's own logs at {@code DEBUG} reveal nothing.
 *
 * @param value 32 lowercase hexadecimal characters
 */
public record JobInstanceId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{32}");
    private static final int BYTES_KEPT = 16;

    public JobInstanceId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("a job instance id is 32 lowercase hexadecimal characters");
        }
    }

    /**
     * {@code SHA-256("v1|" + jobName + "|" + parts joined by "|")}, truncated to its first 16 bytes.
     * Deterministic, sensitive to the job name and to each part in its position, and opaque.
     *
     * @param jobName a valid task name
     * @param parts the identifiers that make the work unique, such as institution, account and request
     */
    public static JobInstanceId derive(String jobName, UUID... parts) {
        JobType.requireValidName(jobName);
        Objects.requireNonNull(parts, "parts");
        if (parts.length == 0) {
            throw new IllegalArgumentException("an instance is derived from at least one part");
        }
        StringBuilder text = new StringBuilder("v1|").append(jobName);
        for (UUID part : parts) {
            text.append('|').append(Objects.requireNonNull(part, "part"));
        }
        byte[] digest = Digests.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
        return new JobInstanceId(HexFormat.of().formatHex(Arrays.copyOf(digest, BYTES_KEPT)));
    }
}
