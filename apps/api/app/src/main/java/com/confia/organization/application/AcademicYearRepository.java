package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.AcademicYear;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the academic years of an institution. Every method is scoped to the institution
 * given; the adapter never opens a transaction (the use case runs inside the one of {@code
 * TransactionRunner}).
 *
 * <p>{@link #insert} and {@link #update} translate what the database refuses: a name already taken is
 * a {@code DuplicateNameException} and a second ACTIVE year is an {@code
 * AcademicYearAlreadyActiveException}, so two concurrent requests cannot both win.
 */
public interface AcademicYearRepository {

    /**
     * Takes the lock that serializes every writer of the date ranges of the institution until the
     * transaction ends, so the overlap check that follows cannot race with another writer.
     */
    void lockInstitution(InstitutionId institutionId);

    Optional<AcademicYear> findById(InstitutionId institutionId, UUID id);

    /** The year with status ACTIVE, if any. */
    Optional<AcademicYear> findActive(InstitutionId institutionId);

    /** Every year whose inclusive range shares at least one day with the given inclusive range. */
    List<AcademicYear> findOverlapping(InstitutionId institutionId, LocalDate start, LocalDate end);

    void insert(InstitutionId institutionId, AcademicYear year);

    void update(InstitutionId institutionId, AcademicYear year);

    Page<AcademicYear> list(InstitutionId institutionId, PageRequest request);
}
