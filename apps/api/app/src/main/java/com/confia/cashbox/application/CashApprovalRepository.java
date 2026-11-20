package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashApproval;
import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Output port of the approvals of the closes. It inserts and reads: no operation updates or deletes an approval. */
public interface CashApprovalRepository {

    /** @throws RequestKeyTakenException when the Idempotency-Key was taken by another request */
    void insert(InstitutionId institution, CashApproval approval);

    Optional<CashApproval> findByRequestKey(InstitutionId institution, String requestKey);

    Optional<CashApproval> findBySession(InstitutionId institution, UUID sessionId);

    /** The approvals of the sessions that have one, by session. */
    Map<UUID, CashApproval> findBySessions(InstitutionId institution, Collection<UUID> sessionIds);
}
