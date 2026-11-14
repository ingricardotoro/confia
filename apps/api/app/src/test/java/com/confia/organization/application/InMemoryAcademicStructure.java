package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.AcademicYear;
import com.confia.organization.domain.AcademicYearAlreadyActiveException;
import com.confia.organization.domain.AcademicYearStatus;
import com.confia.organization.domain.DuplicateNameException;
import com.confia.organization.domain.Grade;
import com.confia.organization.domain.Modality;
import com.confia.organization.domain.Section;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The four ports of the academic structure over maps, for the unit tests of the use cases. Each port
 * honors the contract the real adapter honors through the database: a taken name, code or ordinal is a
 * {@code DuplicateNameException} and a second ACTIVE year is an {@code
 * AcademicYearAlreadyActiveException}. Every row is kept with its institution, so a read of another
 * institution finds nothing. {@link #events} records the order of the calls that matter.
 */
final class InMemoryAcademicStructure {

    record Key(UUID institution, UUID id) {
    }

    final List<String> events = new ArrayList<>();
    final Map<Key, AcademicYear> yearRows = new LinkedHashMap<>();
    final Map<Key, Modality> modalityRows = new LinkedHashMap<>();
    final Map<Key, Grade> gradeRows = new LinkedHashMap<>();
    final Map<Key, Section> sectionRows = new LinkedHashMap<>();
    PageRequest lastRequest;

    final AcademicYearRepository years = new Years();
    final ModalityRepository modalities = new Modalities();
    final GradeRepository grades = new Grades();
    final SectionRepository sections = new Sections();

    private static Key key(InstitutionId institution, UUID id) {
        return new Key(institution.value(), id);
    }

    private static <T> List<T> rowsOf(Map<Key, T> rows, InstitutionId institution, Predicate<T> filter) {
        return rows.entrySet().stream().filter(e -> e.getKey().institution().equals(institution.value()))
                .map(Map.Entry::getValue).filter(filter).toList();
    }

    private <T> Page<T> page(List<T> rows, PageRequest request) {
        lastRequest = request;
        return new Page<>(rows, rows.size(), request.page(), request.size());
    }

    /** Puts a row in without any rule, as a test arranges a state. */
    void arrange(InstitutionId institution, AcademicYear year) {
        yearRows.put(key(institution, year.id()), year);
    }

    void arrange(InstitutionId institution, Modality modality) {
        modalityRows.put(key(institution, modality.id()), modality);
    }

    void arrange(InstitutionId institution, Grade grade) {
        gradeRows.put(key(institution, grade.id()), grade);
    }

    void arrange(InstitutionId institution, Section section) {
        sectionRows.put(key(institution, section.id()), section);
    }

    private final class Years implements AcademicYearRepository {

        @Override
        public void lockInstitution(InstitutionId institutionId) {
            events.add("lock");
        }

        @Override
        public Optional<AcademicYear> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(yearRows.get(key(institution, id)));
        }

        @Override
        public Optional<AcademicYear> findActive(InstitutionId institution) {
            events.add("findActive");
            return rowsOf(yearRows, institution, y -> y.status() == AcademicYearStatus.ACTIVE).stream()
                    .findFirst();
        }

        @Override
        public List<AcademicYear> findOverlapping(InstitutionId institution, LocalDate start,
                LocalDate end) {
            events.add("findOverlapping");
            return rowsOf(yearRows, institution, y -> y.overlaps(start, end));
        }

        @Override
        public void insert(InstitutionId institution, AcademicYear year) {
            guard(institution, year);
            yearRows.put(key(institution, year.id()), year);
        }

        @Override
        public void update(InstitutionId institution, AcademicYear year) {
            guard(institution, year);
            yearRows.put(key(institution, year.id()), year);
        }

        private void guard(InstitutionId institution, AcademicYear year) {
            if (!rowsOf(yearRows, institution,
                    y -> !y.id().equals(year.id()) && y.name().equals(year.name())).isEmpty()) {
                throw new DuplicateNameException();
            }
            if (year.status() == AcademicYearStatus.ACTIVE && !rowsOf(yearRows, institution,
                    y -> !y.id().equals(year.id()) && y.status() == AcademicYearStatus.ACTIVE)
                    .isEmpty()) {
                throw new AcademicYearAlreadyActiveException();
            }
        }

        @Override
        public Page<AcademicYear> list(InstitutionId institution, PageRequest request) {
            return page(rowsOf(yearRows, institution, y -> true), request);
        }
    }

    private final class Modalities implements ModalityRepository {

        @Override
        public Optional<Modality> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(modalityRows.get(key(institution, id)));
        }

        @Override
        public void insert(InstitutionId institution, Modality modality) {
            guard(institution, modality);
            modalityRows.put(key(institution, modality.id()), modality);
        }

        @Override
        public void update(InstitutionId institution, Modality modality) {
            guard(institution, modality);
            modalityRows.put(key(institution, modality.id()), modality);
        }

        private void guard(InstitutionId institution, Modality modality) {
            if (!rowsOf(modalityRows, institution,
                    m -> !m.id().equals(modality.id()) && m.code().equals(modality.code())).isEmpty()) {
                throw new DuplicateNameException();
            }
        }

        @Override
        public Page<Modality> list(InstitutionId institution, PageRequest request) {
            return page(rowsOf(modalityRows, institution, m -> true), request);
        }
    }

    private final class Grades implements GradeRepository {

        @Override
        public Optional<Grade> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(gradeRows.get(key(institution, id)));
        }

        @Override
        public void insert(InstitutionId institution, Grade grade) {
            guard(institution, grade);
            gradeRows.put(key(institution, grade.id()), grade);
        }

        @Override
        public void update(InstitutionId institution, Grade grade) {
            guard(institution, grade);
            gradeRows.put(key(institution, grade.id()), grade);
        }

        private void guard(InstitutionId institution, Grade grade) {
            if (!rowsOf(gradeRows, institution, g -> !g.id().equals(grade.id())
                    && g.modalityId().equals(grade.modalityId())
                    && (g.name().equals(grade.name()) || g.ordinal() == grade.ordinal())).isEmpty()) {
                throw new DuplicateNameException();
            }
        }

        @Override
        public Page<Grade> list(InstitutionId institution, UUID modalityId, PageRequest request) {
            return page(rowsOf(gradeRows, institution,
                    g -> modalityId == null || g.modalityId().equals(modalityId)), request);
        }
    }

    private final class Sections implements SectionRepository {

        @Override
        public Optional<Section> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(sectionRows.get(key(institution, id)));
        }

        @Override
        public void insert(InstitutionId institution, Section section) {
            guard(institution, section);
            sectionRows.put(key(institution, section.id()), section);
        }

        @Override
        public void update(InstitutionId institution, Section section) {
            guard(institution, section);
            sectionRows.put(key(institution, section.id()), section);
        }

        private void guard(InstitutionId institution, Section section) {
            if (!rowsOf(sectionRows, institution, s -> !s.id().equals(section.id())
                    && s.academicYearId().equals(section.academicYearId())
                    && s.gradeId().equals(section.gradeId()) && s.name().equals(section.name()))
                    .isEmpty()) {
                throw new DuplicateNameException();
            }
        }

        @Override
        public Page<Section> list(InstitutionId institution, UUID academicYearId, UUID gradeId,
                PageRequest request) {
            return page(rowsOf(sectionRows, institution,
                    s -> (academicYearId == null || s.academicYearId().equals(academicYearId))
                            && (gradeId == null || s.gradeId().equals(gradeId))), request);
        }
    }
}
