package com.confia.shared.jobs;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One occurrence of a recurring job (design.md decision 7). Its {@code runId} is the correlation and
 * the request of everything the occurrence does, in every institution (scenario BJ66).
 *
 * @param taskName the recurring job name
 * @param runId identifies this occurrence
 * @param startedAt when the occurrence began
 */
public record RecurringRun(String taskName, UUID runId, Instant startedAt) {

    public RecurringRun {
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(startedAt, "startedAt");
    }

    /** The context a transaction for {@code institution} opens with: the {@code system} actor, no id. */
    public SecurityContext systemContextFor(InstitutionId institution) {
        return new SecurityContext("", "system", institution.value().toString(), runId.toString());
    }
}
