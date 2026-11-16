package com.confia.catalog.application;

import com.confia.catalog.domain.PaymentConcept;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the payment concepts of an institution. A code already taken (whatever its capitals) is refused by
 * {@link #insert} as a {@code DuplicateCodeException}. There is no delete.
 */
public interface PaymentConceptRepository {

    Optional<PaymentConcept> findById(InstitutionId institutionId, UUID id);

    void insert(InstitutionId institutionId, PaymentConcept concept);

    /** Writes the editable members; the code is never written. */
    void update(InstitutionId institutionId, PaymentConcept concept);

    Page<PaymentConcept> list(InstitutionId institutionId, PageRequest request, PaymentConceptFilter filter);
}
