package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.students.domain.Student;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Output port of the students of an institution. A code already taken is refused by {@link #insert} and
 * {@link #update} as a {@code DuplicateStudentCodeException}, and a document already held by another
 * student as a {@code DuplicateNationalIdException}; an {@link #update} of a student that does not exist
 * is a {@code ResourceNotFoundException}. Nothing is ever deleted.
 */
public interface StudentRepository {

    Optional<Student> findById(InstitutionId institutionId, UUID id);

    /**
     * The next number of the code counter of the institution, taking a lock on the counter that lasts
     * until the end of the transaction, so two concurrent creations never get the same number and a
     * rollback gives the number back.
     */
    long nextCodeSequence(InstitutionId institutionId);

    boolean codeExists(InstitutionId institutionId, String code);

    /** Stores a new student; {@code document} is null for a student without national identity document. */
    void insert(InstitutionId institutionId, Student student, ProtectedNationalId document);

    /**
     * Stores a new student that comes from a bulk import, with the reference it had in the source system. A reference
     * the institution already has is refused as a {@code DuplicateExternalRefException}, whatever transaction holds it.
     */
    void insertImported(InstitutionId institutionId, Student student, ProtectedNationalId document,
            String externalRef);

    /** Which of these references the institution already has; one read, nothing locked. */
    Set<String> existingExternalRefs(InstitutionId institutionId, Collection<String> externalRefs);

    /** Which of these blind indexes the institution already holds; one read, nothing locked. */
    Set<String> existingBlindIndexes(InstitutionId institutionId, Collection<String> blindIndexes);

    void update(InstitutionId institutionId, Student student, NationalIdStorage storage);

    /**
     * Locks, until the end of the transaction, and returns up to {@code limit} stored documents whose blind
     * index does not carry {@code version}: the rows a re-index has to recompute.
     */
    List<IndexedNationalId> lockNationalIdsNotIndexedWith(InstitutionId institutionId, String version, int limit);

    /** Replaces only the blind index of a student that has a document; the document itself is untouched. */
    void replaceBlindIndex(InstitutionId institutionId, UUID studentId, String blindIndex);

    Page<Student> list(InstitutionId institutionId, PageRequest request, StudentFilter filter);
}
