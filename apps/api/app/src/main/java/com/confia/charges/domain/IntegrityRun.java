package com.confia.charges.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The result of one nightly integrity check of one institution: when it ran, how much it checked and how many discrepancies
 * it confirmed. It is born once and never changes. The status is derived from the count: {@code OK} when there are none.
 */
public record IntegrityRun(UUID id, Instant startedAt, Instant finishedAt, int chargesChecked, int postingsChecked,
        int discrepancyCount) {

    public static final String OK = "OK";
    public static final String DISCREPANCIES = "DISCREPANCIES";

    public IntegrityRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        if (finishedAt.isBefore(startedAt) || chargesChecked < 0 || postingsChecked < 0 || discrepancyCount < 0) {
            throw new IllegalArgumentException("an integrity run ends after it starts and counts are not negative");
        }
    }

    public String status() {
        return discrepancyCount == 0 ? OK : DISCREPANCIES;
    }
}
