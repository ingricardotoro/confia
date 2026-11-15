package com.confia.students.application.directory;

import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port, read-only, through which other modules ask {@code students} about students. Every method is
 * scoped to the institution given, and a student of another institution is never found. Nothing here writes: the
 * one operation with an effect, {@link #lock}, only takes a row lock that lasts until the end of the transaction.
 */
public interface StudentDirectory {

    /** The student, or empty when it does not exist in the institution. */
    Optional<StudentRef> find(InstitutionId institutionId, UUID studentId);

    /**
     * The student, locked until the end of the transaction ({@code SELECT ... FOR UPDATE} on its row), so two
     * transactions that decide on something that depends on the student take turns. Empty when it does not exist in
     * the institution.
     */
    Optional<StudentRef> lock(InstitutionId institutionId, UUID studentId);

    /** The students with these identifiers, by identifier, in one query; unknown identifiers are absent. */
    Map<UUID, StudentRef> findAll(InstitutionId institutionId, Collection<UUID> studentIds);

    /**
     * The students imported with these references of the source system (the {@code legacyId} of the import file), by
     * reference, in one query; a reference nobody holds is absent. It reveals no more than {@link StudentRef} does.
     */
    Map<String, StudentRef> findByExternalRefs(InstitutionId institutionId, Collection<String> externalRefs);
}
