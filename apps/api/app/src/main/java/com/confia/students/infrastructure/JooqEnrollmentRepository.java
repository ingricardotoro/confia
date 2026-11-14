package com.confia.students.infrastructure;

import static confia.generated.jooq.tables.StudentsEnrollment.STUDENTS_ENROLLMENT;
import static confia.generated.jooq.tables.StudentsEnrollmentTransfer.STUDENTS_ENROLLMENT_TRANSFER;
import static confia.generated.jooq.tables.StudentsStudent.STUDENTS_STUDENT;

import com.confia.kernel.InstitutionId;
import com.confia.students.application.EnrollmentFilter;
import com.confia.students.application.EnrollmentRepository;
import com.confia.students.application.EnrollmentRow;
import com.confia.students.application.Page;
import com.confia.students.application.PageRequest;
import com.confia.students.domain.Enrollment;
import com.confia.students.domain.EnrollmentStatus;
import com.confia.students.domain.EnrollmentTransfer;
import com.confia.students.domain.ResourceNotFoundException;
import confia.generated.jooq.tables.records.StudentsEnrollmentRecord;
import confia.generated.jooq.tables.records.StudentsEnrollmentTransferRecord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link EnrollmentRepository} over {@code students_enrollment} and {@code
 * students_enrollment_transfer}, with the names of the student read from {@code students_student} (a table
 * of this same module) for the lists. It never opens a transaction and never sets the institution of the
 * session; row-level security does the second filter on every statement. It offers no deletion, and the
 * history of transfers only an insert and a read: the database grants nothing else.
 */
public final class JooqEnrollmentRepository implements EnrollmentRepository {

    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "enrolledOn", STUDENTS_ENROLLMENT.ENROLLED_ON,
            "studentLastName", STUDENTS_STUDENT.LAST_NAME,
            "status", STUDENTS_ENROLLMENT.STATUS);

    private final DSLContext dsl;

    public JooqEnrollmentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Enrollment> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(STUDENTS_ENROLLMENT)
                .where(STUDENTS_ENROLLMENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_ENROLLMENT.ID.eq(id))
                .fetchOptional().map(JooqEnrollmentRepository::toDomain);
    }

    @Override
    public Optional<Enrollment> lockById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(STUDENTS_ENROLLMENT)
                .where(STUDENTS_ENROLLMENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_ENROLLMENT.ID.eq(id))
                .forUpdate()
                .fetchOptional().map(JooqEnrollmentRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, Enrollment enrollment) {
        EnrollmentWrites.translating(() -> dsl.insertInto(STUDENTS_ENROLLMENT)
                .set(STUDENTS_ENROLLMENT.INSTITUTION_ID, institutionId.value())
                .set(STUDENTS_ENROLLMENT.ID, enrollment.id())
                .set(STUDENTS_ENROLLMENT.STUDENT_ID, enrollment.studentId())
                .set(STUDENTS_ENROLLMENT.SECTION_ID, enrollment.sectionId())
                .set(STUDENTS_ENROLLMENT.ACADEMIC_YEAR_ID, enrollment.academicYearId())
                .set(STUDENTS_ENROLLMENT.ENROLLED_ON, enrollment.enrolledOn())
                .set(STUDENTS_ENROLLMENT.STATUS, enrollment.status().name())
                .set(STUDENTS_ENROLLMENT.WITHDRAWN_ON, enrollment.withdrawnOn())
                .set(STUDENTS_ENROLLMENT.WITHDRAWAL_REASON, enrollment.withdrawalReason())
                .set(STUDENTS_ENROLLMENT.CREATED_AT, StudentWrites.toDatabase(enrollment.createdAt()))
                .set(STUDENTS_ENROLLMENT.UPDATED_AT, StudentWrites.toDatabase(enrollment.updatedAt()))
                .execute());
    }

    @Override
    public void update(InstitutionId institutionId, Enrollment enrollment) {
        EnrollmentWrites.translating(() -> {
            int rows = dsl.update(STUDENTS_ENROLLMENT)
                    .set(STUDENTS_ENROLLMENT.SECTION_ID, enrollment.sectionId())
                    .set(STUDENTS_ENROLLMENT.STATUS, enrollment.status().name())
                    .set(STUDENTS_ENROLLMENT.WITHDRAWN_ON, enrollment.withdrawnOn())
                    .set(STUDENTS_ENROLLMENT.WITHDRAWAL_REASON, enrollment.withdrawalReason())
                    .set(STUDENTS_ENROLLMENT.UPDATED_AT, StudentWrites.toDatabase(enrollment.updatedAt()))
                    .where(STUDENTS_ENROLLMENT.INSTITUTION_ID.eq(institutionId.value()))
                    .and(STUDENTS_ENROLLMENT.ID.eq(enrollment.id()))
                    .execute();
            if (rows == 0) {
                throw new ResourceNotFoundException();
            }
        });
    }

    @Override
    public void insertTransfer(InstitutionId institutionId, EnrollmentTransfer transfer) {
        dsl.insertInto(STUDENTS_ENROLLMENT_TRANSFER)
                .set(STUDENTS_ENROLLMENT_TRANSFER.INSTITUTION_ID, institutionId.value())
                .set(STUDENTS_ENROLLMENT_TRANSFER.ID, transfer.id())
                .set(STUDENTS_ENROLLMENT_TRANSFER.ENROLLMENT_ID, transfer.enrollmentId())
                .set(STUDENTS_ENROLLMENT_TRANSFER.FROM_SECTION_ID, transfer.fromSectionId())
                .set(STUDENTS_ENROLLMENT_TRANSFER.TO_SECTION_ID, transfer.toSectionId())
                .set(STUDENTS_ENROLLMENT_TRANSFER.TRANSFERRED_ON, transfer.transferredOn())
                .set(STUDENTS_ENROLLMENT_TRANSFER.REASON, transfer.reason())
                .set(STUDENTS_ENROLLMENT_TRANSFER.ACTOR_ID, transfer.actorId())
                .set(STUDENTS_ENROLLMENT_TRANSFER.CREATED_AT, StudentWrites.toDatabase(transfer.createdAt()))
                .execute();
    }

    @Override
    public List<EnrollmentTransfer> transfersOf(InstitutionId institutionId, UUID enrollmentId) {
        return dsl.selectFrom(STUDENTS_ENROLLMENT_TRANSFER)
                .where(STUDENTS_ENROLLMENT_TRANSFER.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_ENROLLMENT_TRANSFER.ENROLLMENT_ID.eq(enrollmentId))
                .orderBy(STUDENTS_ENROLLMENT_TRANSFER.TRANSFERRED_ON.asc(),
                        STUDENTS_ENROLLMENT_TRANSFER.CREATED_AT.asc(), STUDENTS_ENROLLMENT_TRANSFER.ID.asc())
                .fetch(JooqEnrollmentRepository::toDomain);
    }

    @Override
    public int countActiveInSection(InstitutionId institutionId, UUID sectionId) {
        return dsl.fetchCount(STUDENTS_ENROLLMENT, STUDENTS_ENROLLMENT.INSTITUTION_ID.eq(institutionId.value())
                .and(STUDENTS_ENROLLMENT.SECTION_ID.eq(sectionId))
                .and(STUDENTS_ENROLLMENT.STATUS.eq(EnrollmentStatus.ACTIVE.name())));
    }

    @Override
    public int countActiveInYear(InstitutionId institutionId, UUID academicYearId) {
        return dsl.fetchCount(STUDENTS_ENROLLMENT, STUDENTS_ENROLLMENT.INSTITUTION_ID.eq(institutionId.value())
                .and(STUDENTS_ENROLLMENT.ACADEMIC_YEAR_ID.eq(academicYearId))
                .and(STUDENTS_ENROLLMENT.STATUS.eq(EnrollmentStatus.ACTIVE.name())));
    }

    @Override
    public boolean hasActiveInYear(InstitutionId institutionId, UUID studentId, UUID academicYearId) {
        return dsl.fetchExists(STUDENTS_ENROLLMENT, STUDENTS_ENROLLMENT.INSTITUTION_ID.eq(institutionId.value())
                .and(STUDENTS_ENROLLMENT.STUDENT_ID.eq(studentId))
                .and(STUDENTS_ENROLLMENT.ACADEMIC_YEAR_ID.eq(academicYearId))
                .and(STUDENTS_ENROLLMENT.STATUS.eq(EnrollmentStatus.ACTIVE.name())));
    }

    @Override
    public List<ActiveSeat> activeSeatsOf(InstitutionId institutionId, Collection<UUID> studentIds) {
        if (studentIds.isEmpty()) {
            return List.of();
        }
        return dsl.select(STUDENTS_ENROLLMENT.STUDENT_ID, STUDENTS_ENROLLMENT.ACADEMIC_YEAR_ID,
                        STUDENTS_ENROLLMENT.SECTION_ID)
                .from(STUDENTS_ENROLLMENT)
                .where(STUDENTS_ENROLLMENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_ENROLLMENT.STUDENT_ID.in(studentIds))
                .and(STUDENTS_ENROLLMENT.STATUS.eq(EnrollmentStatus.ACTIVE.name()))
                .fetch(row -> new ActiveSeat(row.get(STUDENTS_ENROLLMENT.STUDENT_ID),
                        row.get(STUDENTS_ENROLLMENT.ACADEMIC_YEAR_ID), row.get(STUDENTS_ENROLLMENT.SECTION_ID)));
    }

    @Override
    public Page<EnrollmentRow> list(InstitutionId institutionId, PageRequest request, EnrollmentFilter filter) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(STUDENTS_ENROLLMENT.INSTITUTION_ID.eq(institutionId.value()));
        if (filter.academicYearId() != null) {
            conditions.add(STUDENTS_ENROLLMENT.ACADEMIC_YEAR_ID.eq(filter.academicYearId()));
        }
        if (filter.sectionIds() != null) {
            // An empty collection keeps nothing: jOOQ renders IN () as a false condition.
            conditions.add(STUDENTS_ENROLLMENT.SECTION_ID.in(filter.sectionIds()));
        }
        if (filter.status() != null) {
            conditions.add(STUDENTS_ENROLLMENT.STATUS.eq(filter.status().name()));
        }
        Condition scope = DSL.and(conditions);
        long total = dsl.selectCount().from(STUDENTS_ENROLLMENT).join(STUDENTS_STUDENT).on(joinStudent())
                .where(scope).fetchSingle().value1();
        List<EnrollmentRow> rows = dsl.select(STUDENTS_ENROLLMENT.fields())
                .select(STUDENTS_STUDENT.STUDENT_CODE, STUDENTS_STUDENT.FIRST_NAME, STUDENTS_STUDENT.LAST_NAME)
                .from(STUDENTS_ENROLLMENT).join(STUDENTS_STUDENT).on(joinStudent())
                .where(scope)
                .orderBy(order(request), STUDENTS_STUDENT.LAST_NAME.asc(), STUDENTS_STUDENT.FIRST_NAME.asc(),
                        STUDENTS_ENROLLMENT.ENROLLED_ON.asc(), STUDENTS_ENROLLMENT.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqEnrollmentRepository::toRow);
        return new Page<>(rows, total, request.page(), request.size());
    }

    @Override
    public List<EnrollmentRow> ofStudent(InstitutionId institutionId, UUID studentId) {
        return dsl.select(STUDENTS_ENROLLMENT.fields())
                .select(STUDENTS_STUDENT.STUDENT_CODE, STUDENTS_STUDENT.FIRST_NAME, STUDENTS_STUDENT.LAST_NAME)
                .from(STUDENTS_ENROLLMENT).join(STUDENTS_STUDENT).on(joinStudent())
                .where(STUDENTS_ENROLLMENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_ENROLLMENT.STUDENT_ID.eq(studentId))
                .orderBy(STUDENTS_ENROLLMENT.ENROLLED_ON.desc(), STUDENTS_ENROLLMENT.CREATED_AT.desc(),
                        STUDENTS_ENROLLMENT.ID.asc())
                .fetch(JooqEnrollmentRepository::toRow);
    }

    private static Condition joinStudent() {
        return STUDENTS_STUDENT.INSTITUTION_ID.eq(STUDENTS_ENROLLMENT.INSTITUTION_ID)
                .and(STUDENTS_STUDENT.ID.eq(STUDENTS_ENROLLMENT.STUDENT_ID));
    }

    private static SortField<?> order(PageRequest request) {
        Field<?> field = SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static EnrollmentRow toRow(Record row) {
        return new EnrollmentRow(toDomain(row.into(STUDENTS_ENROLLMENT)), row.get(STUDENTS_STUDENT.STUDENT_CODE),
                row.get(STUDENTS_STUDENT.FIRST_NAME), row.get(STUDENTS_STUDENT.LAST_NAME));
    }

    private static Enrollment toDomain(StudentsEnrollmentRecord row) {
        return new Enrollment(row.getId(), row.getStudentId(), row.getSectionId(), row.getAcademicYearId(),
                row.getEnrolledOn(), EnrollmentStatus.valueOf(row.getStatus()), row.getWithdrawnOn(),
                row.getWithdrawalReason(), StudentWrites.fromDatabase(row.getCreatedAt()),
                StudentWrites.fromDatabase(row.getUpdatedAt()));
    }

    private static EnrollmentTransfer toDomain(StudentsEnrollmentTransferRecord row) {
        return new EnrollmentTransfer(row.getId(), row.getEnrollmentId(), row.getFromSectionId(),
                row.getToSectionId(), row.getTransferredOn(), row.getReason(), row.getActorId(),
                StudentWrites.fromDatabase(row.getCreatedAt()));
    }
}
