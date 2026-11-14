package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.Modality;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the modalities of an institution. A code already taken is refused by {@link #insert}
 * and {@link #update} as a {@code DuplicateNameException}.
 */
public interface ModalityRepository {

    Optional<Modality> findById(InstitutionId institutionId, UUID id);

    void insert(InstitutionId institutionId, Modality modality);

    void update(InstitutionId institutionId, Modality modality);

    Page<Modality> list(InstitutionId institutionId, PageRequest request);
}
