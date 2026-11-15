package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.SectionDirectory;
import com.confia.organization.application.directory.SectionNames;
import com.confia.organization.application.directory.SectionRef;
import com.confia.students.domain.DuplicateActiveEnrollmentException;
import com.confia.students.domain.Enrollment;
import com.confia.students.domain.EnrollmentStatus;
import com.confia.students.domain.EnrollmentTransfer;
import com.confia.students.domain.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The ports of the enrollments and of the academic structure over maps, for the unit tests of the use case.
 * The repository honors what the real adapter honors through the database: a second active enrollment of a
 * student in a year is refused, every row is kept with its institution so a read of another institution
 * finds nothing, and the history is only inserted. Both record every lock in {@link #events}, in order, so a
 * test can prove the order in which the use case locks.
 */
final class InMemoryEnrollments {

    record Key(UUID institution, UUID id) {
    }

    final List<String> events = new ArrayList<>();
    final Map<Key, Enrollment> rows = new LinkedHashMap<>();
    final List<EnrollmentTransfer> history = new ArrayList<>();
    final Map<Key, SectionRef> sectionRows = new HashMap<>();
    final Map<UUID, UUID> activeYears = new HashMap<>();
    private final InMemoryStudents studentStore;
    PageRequest lastRequest;
    EnrollmentFilter lastFilter;

    final EnrollmentRepository enrollments = new Repository();
    final SectionDirectory sections = new Directory();

    InMemoryEnrollments(InMemoryStudents studentStore) {
        this.studentStore = studentStore;
    }

    void arrange(InstitutionId institution, SectionRef section) {
        sectionRows.put(new Key(institution.value(), section.sectionId()), section);
    }

    void arrange(InstitutionId institution, Enrollment enrollment) {
        rows.put(new Key(institution.value(), enrollment.id()), enrollment);
    }

    List<Enrollment> rowsOf(InstitutionId institution) {
        return rows.entrySet().stream().filter(e -> e.getKey().institution().equals(institution.value()))
                .map(Map.Entry::getValue).toList();
    }

    private final class Repository implements EnrollmentRepository {

        @Override
        public Optional<Enrollment> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(rows.get(new Key(institution.value(), id)));
        }

        @Override
        public Optional<Enrollment> lockById(InstitutionId institution, UUID id) {
            events.add("lock-enrollment");
            return findById(institution, id);
        }

        @Override
        public void insert(InstitutionId institution, Enrollment enrollment) {
            events.add("insert");
            if (hasActiveInYear(institution, enrollment.studentId(), enrollment.academicYearId())) {
                throw new DuplicateActiveEnrollmentException();
            }
            rows.put(new Key(institution.value(), enrollment.id()), enrollment);
        }

        @Override
        public void update(InstitutionId institution, Enrollment enrollment) {
            events.add("update");
            Key key = new Key(institution.value(), enrollment.id());
            if (!rows.containsKey(key)) {
                throw new ResourceNotFoundException();
            }
            rows.put(key, enrollment);
        }

        @Override
        public void insertTransfer(InstitutionId institution, EnrollmentTransfer transfer) {
            events.add("insert-transfer");
            history.add(transfer);
        }

        @Override
        public List<EnrollmentTransfer> transfersOf(InstitutionId institution, UUID enrollmentId) {
            return history.stream().filter(t -> t.enrollmentId().equals(enrollmentId))
                    .filter(t -> rows.containsKey(new Key(institution.value(), enrollmentId))).toList();
        }

        @Override
        public int countActiveInSection(InstitutionId institution, UUID sectionId) {
            events.add("count");
            return (int) rowsOf(institution).stream().filter(e -> e.sectionId().equals(sectionId))
                    .filter(e -> e.status() == EnrollmentStatus.ACTIVE).count();
        }

        @Override
        public int countActiveInYear(InstitutionId institution, UUID academicYearId) {
            return (int) rowsOf(institution).stream().filter(e -> e.academicYearId().equals(academicYearId))
                    .filter(e -> e.status() == EnrollmentStatus.ACTIVE).count();
        }

        @Override
        public boolean hasActiveInYear(InstitutionId institution, UUID studentId, UUID academicYearId) {
            return rowsOf(institution).stream().anyMatch(e -> e.studentId().equals(studentId)
                    && e.academicYearId().equals(academicYearId) && e.status() == EnrollmentStatus.ACTIVE);
        }

        @Override
        public List<ActiveSeat> activeSeatsOf(InstitutionId institution, Collection<UUID> studentIds) {
            return rowsOf(institution).stream()
                    .filter(e -> studentIds.contains(e.studentId()) && e.status() == EnrollmentStatus.ACTIVE)
                    .map(e -> new ActiveSeat(e.studentId(), e.academicYearId(), e.sectionId())).toList();
        }

        @Override
        public Page<EnrollmentRow> list(InstitutionId institution, PageRequest request, EnrollmentFilter filter) {
            lastRequest = request;
            lastFilter = filter;
            Comparator<EnrollmentRow> order = switch (request.sortField()) {
                case "enrolledOn" -> Comparator.comparing(r -> r.enrollment().enrolledOn());
                case "status" -> Comparator.comparing(r -> r.enrollment().status());
                default -> Comparator.comparing(EnrollmentRow::studentLastName);
            };
            List<EnrollmentRow> matching = rowsOf(institution).stream()
                    .filter(e -> filter.academicYearId() == null || e.academicYearId().equals(filter.academicYearId()))
                    .filter(e -> filter.sectionIds() == null || filter.sectionIds().contains(e.sectionId()))
                    .filter(e -> filter.status() == null || e.status() == filter.status())
                    .map(e -> row(institution, e))
                    .sorted(request.ascending() ? order : order.reversed())
                    .toList();
            int from = (int) Math.min(request.offset(), matching.size());
            int to = Math.min(from + request.size(), matching.size());
            return new Page<>(matching.subList(from, to), matching.size(), request.page(), request.size());
        }

        @Override
        public List<EnrollmentRow> ofStudent(InstitutionId institution, UUID studentId) {
            return rowsOf(institution).stream().filter(e -> e.studentId().equals(studentId))
                    .sorted(Comparator.comparing(Enrollment::enrolledOn).reversed())
                    .map(e -> row(institution, e)).toList();
        }

        private EnrollmentRow row(InstitutionId institution, Enrollment e) {
            var student = studentStore.students.findById(institution, e.studentId()).orElseThrow();
            return new EnrollmentRow(e, student.studentCode(), student.firstName(), student.lastName());
        }
    }

    private final class Directory implements SectionDirectory {

        @Override
        public Optional<SectionRef> find(InstitutionId institution, UUID sectionId) {
            return Optional.ofNullable(sectionRows.get(new Key(institution.value(), sectionId)));
        }

        @Override
        public Optional<SectionRef> lock(InstitutionId institution, UUID sectionId) {
            events.add("lock-section");
            return find(institution, sectionId);
        }

        @Override
        public Map<UUID, SectionRef> findAll(InstitutionId institution, Collection<UUID> sectionIds) {
            events.add("find-all");
            Map<UUID, SectionRef> found = new HashMap<>();
            for (UUID id : sectionIds) {
                find(institution, id).ifPresent(ref -> found.put(id, ref));
            }
            return found;
        }

        @Override
        public List<UUID> sectionIdsOfGrade(InstitutionId institution, UUID academicYearId, UUID gradeId) {
            return sectionRows.entrySet().stream().filter(e -> e.getKey().institution().equals(institution.value()))
                    .map(Map.Entry::getValue)
                    .filter(s -> s.gradeId().equals(gradeId))
                    .filter(s -> academicYearId == null || s.academicYearId().equals(academicYearId))
                    .map(SectionRef::sectionId).toList();
        }

        @Override
        public Map<SectionNames, List<SectionRef>> findByNames(InstitutionId institution,
                Collection<SectionNames> names) {
            Map<SectionNames, List<SectionRef>> found = new HashMap<>();
            sectionRows.entrySet().stream().filter(e -> e.getKey().institution().equals(institution.value()))
                    .map(Map.Entry::getValue).forEach(ref -> {
                        SectionNames key = new SectionNames(ref.academicYearName(), ref.modalityName(),
                                ref.gradeName(), ref.name());
                        if (names.contains(key)) {
                            found.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
                        }
                    });
            return found;
        }

        @Override
        public boolean academicYearExists(InstitutionId institution, UUID academicYearId) {
            return sectionRows.entrySet().stream().filter(e -> e.getKey().institution().equals(institution.value()))
                    .anyMatch(e -> e.getValue().academicYearId().equals(academicYearId));
        }

        @Override
        public Optional<UUID> activeAcademicYearId(InstitutionId institution) {
            return Optional.ofNullable(activeYears.get(institution.value()));
        }
    }
}
