package com.confia.guardians.application;

import com.confia.guardians.domain.Guardian;
import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the guardians of an institution. A document already held by another guardian is refused by
 * {@link #insert} and {@link #update} as a {@code DuplicateNationalIdException}; an {@link #update} of a guardian
 * that does not exist is a {@code ResourceNotFoundException}. Nothing is ever deleted.
 */
public interface GuardianRepository {

    Optional<Guardian> findById(InstitutionId institutionId, UUID id);

    /**
     * The guardian, locked until the end of the transaction ({@code SELECT ... FOR UPDATE}), so an edit sees
     * what a concurrent edit left.
     */
    Optional<Guardian> lockById(InstitutionId institutionId, UUID id);

    /** The guardians with these identifiers that exist in the institution; unknown identifiers are absent. */
    List<Guardian> findAll(InstitutionId institutionId, Collection<UUID> ids);

    /**
     * The same guardians, each locked in share mode until the end of the transaction ({@code FOR SHARE}, in
     * identifier order): a concurrent deactivation waits for the replacement of links that is judging them.
     */
    List<Guardian> lockAllShared(InstitutionId institutionId, Collection<UUID> ids);

    /**
     * The next number of the code counter of the institution, taking a lock on the counter that lasts until the
     * end of the transaction, so two concurrent creations never get the same number and a rollback gives the
     * number back.
     */
    long nextCodeSequence(InstitutionId institutionId);

    boolean codeExists(InstitutionId institutionId, String code);

    /** Stores a new guardian; the document and the RTN are null when the guardian has none. */
    void insert(InstitutionId institutionId, Guardian guardian, ProtectedNationalId document, String rtnEncrypted);

    void update(InstitutionId institutionId, Guardian guardian, Storage<ProtectedNationalId> document,
            Storage<String> rtn);

    Page<Guardian> list(InstitutionId institutionId, PageRequest request, GuardianFilter filter);
}
