package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashClose;
import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Output port of the closes of the sessions. It inserts and reads: no operation updates or deletes a close (INV-23). */
public interface CashCloseRepository {

    /** @throws RequestKeyTakenException when the Idempotency-Key was taken by another request */
    void insert(InstitutionId institution, CashClose close);

    Optional<CashClose> findByRequestKey(InstitutionId institution, String requestKey);

    Optional<CashClose> findBySession(InstitutionId institution, UUID sessionId);

    /** The closes of the sessions that have one, by session. */
    Map<UUID, CashClose> findBySessions(InstitutionId institution, Collection<UUID> sessionIds);
}
