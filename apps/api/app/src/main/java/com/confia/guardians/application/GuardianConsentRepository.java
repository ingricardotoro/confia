package com.confia.guardians.application;

import com.confia.guardians.domain.ConsentEvent;
import com.confia.kernel.InstitutionId;
import java.util.List;
import java.util.UUID;

/**
 * Output port of the consent events of the guardians of an institution. It only appends and reads: the events are
 * evidence and are never updated or deleted, and the port declares no operation that could. The use case holds the
 * lock of the guardian row ({@link GuardianRepository#lockById}) before it reads and appends, so the events of one
 * guardian are written one at a time.
 */
public interface GuardianConsentRepository {

    /** The latest event of each combination of channel and purpose of the guardian, oldest first. */
    List<ConsentEvent> latestOfGuardian(InstitutionId institutionId, UUID guardianId);

    /** The newest events of the guardian first, at most {@code limit}. */
    List<ConsentEvent> recentOfGuardian(InstitutionId institutionId, UUID guardianId, int limit);

    /**
     * Appends an event; the table of the events is append-only. {@code requestId} is the id of the request that
     * recorded it, the same one its audit entry carries.
     */
    void append(InstitutionId institutionId, ConsentEvent event, UUID requestId);
}
