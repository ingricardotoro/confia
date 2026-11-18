package com.confia.charges.application;

import com.confia.charges.domain.IntegrityRun;
import java.time.Instant;
import java.util.UUID;

/** The result of one nightly integrity check as the web layer may see it. */
public record IntegrityRunView(UUID id, Instant startedAt, Instant finishedAt, String status, int chargesChecked,
        int postingsChecked, int discrepancyCount) {

    static IntegrityRunView of(IntegrityRun run) {
        return new IntegrityRunView(run.id(), run.startedAt(), run.finishedAt(), run.status(), run.chargesChecked(),
                run.postingsChecked(), run.discrepancyCount());
    }
}
