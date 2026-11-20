package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashSession;
import com.confia.cashbox.domain.CashSessionStatus;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the cash sessions. It inserts, reads and advances the status along the allowed transitions: no operation edits
 * another column or deletes, and the database refuses both to every role (INV-21, INV-22).
 */
public interface CashSessionRepository {

    /**
     * @throws RequestKeyTakenException when the Idempotency-Key or the one-open-session index was taken by another request
     */
    void insert(InstitutionId institution, CashSession session);

    Optional<CashSession> findByRequestKey(InstitutionId institution, String requestKey);

    Optional<CashSession> findById(InstitutionId institution, UUID id);

    /** Reads the session and takes {@code FOR UPDATE}, so that a movement, a close and an approval of it serialize. */
    Optional<CashSession> findForUpdate(InstitutionId institution, UUID id);

    Optional<CashSession> findOpenByCashier(InstitutionId institution, UUID cashier);

    /** Moves the status forward; the database refuses any other move and one without the record behind it. */
    void advanceStatus(InstitutionId institution, UUID id, CashSessionStatus to);

    Page<CashSession> list(InstitutionId institution, SessionFilter filter, PageRequest page);
}
