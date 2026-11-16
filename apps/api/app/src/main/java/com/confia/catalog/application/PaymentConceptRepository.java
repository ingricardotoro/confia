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

    /**
     * Writes the editable members and the new version; the code is never written.
     *
     * @param expectedVersion the version the row must still have
     * @throws com.confia.catalog.domain.StaleVersionException when the row no longer has it
     * @throws com.confia.catalog.domain.ResourceNotFoundException when the row does not exist in the institution
     */
    void update(InstitutionId institutionId, PaymentConcept concept, long expectedVersion);

    Page<PaymentConcept> list(InstitutionId institutionId, PageRequest request, PaymentConceptFilter filter);
}
