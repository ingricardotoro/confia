package com.confia.charges.application;

import com.confia.charges.domain.AccrualRun;
import com.confia.kernel.InstitutionId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the accrual runs. A run is born queued ({@link #insert}) and changes only through {@link #update}, which
 * writes its status, dates, counters and failure code; the database refuses every other change and every exit from a
 * terminal state.
 */
public interface AccrualRunRepository {

    Optional<AccrualRun> findById(InstitutionId institution, UUID id);

    /** The run, locked until the end of the transaction ({@code SELECT ... FOR UPDATE}). */
    Optional<AccrualRun> lockById(InstitutionId institution, UUID id);

    Optional<AccrualRun> findByRequestKey(InstitutionId institution, String requestKey);

    /** The queued or running run of the plan and period, if any. */
    Optional<AccrualRun> findActive(InstitutionId institution, UUID planId, LocalDate periodStart);

    /**
     * Inserts a queued run.
     *
     * @throws com.confia.charges.domain.AccrualRunInProgressException when another run of the plan and period is not
     *     finished
     * @throws RequestKeyTakenException when the request key already exists in the institution
     */
    void insert(InstitutionId institution, AccrualRun run);

    /** Writes the status, the dates, the counters and the failure code of the run. */
    void update(InstitutionId institution, AccrualRun run);

    /** The runs of the institution, most recent first, optionally of one plan. */
    Page<AccrualRun> list(InstitutionId institution, UUID planId, int page, int size);

    /** The most recent run of each period of the plan, by period start. */
    List<AccrualRun> latestByPeriod(InstitutionId institution, UUID planId);

    /** Another run took the request key a moment ago: the caller reads it and decides whether it is a replay. */
    final class RequestKeyTakenException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RequestKeyTakenException() {
            super("the request key is already in use", null, false, false);
        }
    }
}
