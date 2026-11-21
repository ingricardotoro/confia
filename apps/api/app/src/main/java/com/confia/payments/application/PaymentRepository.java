package com.confia.payments.application;

import com.confia.kernel.InstitutionId;
import com.confia.payments.domain.Payment;
import com.confia.payments.domain.PaymentStatus;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the payments. It inserts, reads and advances the status once along the allowed transition: no operation edits
 * another column or deletes, and the database refuses both to every role (INV-19, INV-20, INV-47-style immutability).
 */
public interface PaymentRepository {

    /**
     * @throws RequestKeyTakenException when the Idempotency-Key was taken by another request
     * @throws com.confia.payments.domain.DuplicateTransferReferenceException when the bank and the reference are in use
     */
    void insert(InstitutionId institution, Payment payment);

    Optional<Payment> findById(InstitutionId institution, UUID id);

    /** Reads the payment and takes {@code FOR UPDATE}, so that a confirmation and a rejection of it take turns. */
    Optional<Payment> findForUpdate(InstitutionId institution, UUID id);

    Optional<Payment> findByRequestKey(InstitutionId institution, String requestKey);

    /** Whether a payment that was not rejected already carries this bank and reference, whatever their capitals. */
    boolean transferReferenceInUse(InstitutionId institution, String bankName, String reference);

    /** Moves the status forward; the database refuses any other move and one without the decision behind it. */
    void advanceStatus(InstitutionId institution, UUID id, PaymentStatus to);

    Page<Payment> list(InstitutionId institution, PaymentFilter filter, PageRequest page);
}
