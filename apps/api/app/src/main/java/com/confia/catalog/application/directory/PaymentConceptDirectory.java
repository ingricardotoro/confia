package com.confia.catalog.application.directory;

import com.confia.kernel.InstitutionId;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port, read-only, through which other modules ask {@code catalog} about a payment concept of an institution. It is
 * scoped to the institution given: a concept of another institution is never found, so it looks the same as one that does
 * not exist. It answers nothing else about the concept, and it never writes: the one operation with an effect only takes a
 * row lock that lasts until the end of the transaction.
 */
public interface PaymentConceptDirectory {

    /**
     * The concept, locked in share mode until the end of the transaction ({@code SELECT ... FOR SHARE}): nobody can
     * deactivate or edit it until the caller commits, so a plan item is never added for a concept that is deactivated in
     * between. Empty when it does not exist in the institution.
     */
    Optional<PaymentConceptRef> lockShare(InstitutionId institutionId, UUID paymentConceptId);
}
