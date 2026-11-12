package com.confia.students.infrastructure;

import static confia.generated.jooq.tables.StudentsStudent.STUDENTS_STUDENT;

import com.confia.kernel.InstitutionId;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import com.confia.students.domain.StudentStatus;
import confia.generated.jooq.tables.records.StudentsStudentRecord;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link StudentDirectory}: a student read-only, never with its document. The lock is
 * {@code FOR UPDATE} on the row of the student. It never opens a transaction and never sets the institution of the
 * session; row-level security does the second filter on every statement.
 */
public final class JooqStudentDirectory implements StudentDirectory {

    private final DSLContext dsl;

    public JooqStudentDirectory(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<StudentRef> find(InstitutionId institutionId, UUID studentId) {
        return dsl.selectFrom(STUDENTS_STUDENT)
                .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_STUDENT.ID.eq(studentId))
                .fetchOptional().map(JooqStudentDirectory::toRef);
    }

    @Override
    public Optional<StudentRef> lock(InstitutionId institutionId, UUID studentId) {
        return dsl.selectFrom(STUDENTS_STUDENT)
                .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_STUDENT.ID.eq(studentId))
                .forUpdate()
                .fetchOptional().map(JooqStudentDirectory::toRef);
    }

    @Override
    public Map<UUID, StudentRef> findAll(InstitutionId institutionId, Collection<UUID> studentIds) {
        Map<UUID, StudentRef> found = new HashMap<>();
        if (studentIds.isEmpty()) {
            return found;
        }
        dsl.selectFrom(STUDENTS_STUDENT)
                .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_STUDENT.ID.in(studentIds))
                .fetch().forEach(row -> {
                    StudentRef ref = toRef(row);
                    found.put(ref.studentId(), ref);
                });
        return found;
    }

    private static StudentRef toRef(StudentsStudentRecord row) {
        return new StudentRef(row.getId(), row.getStudentCode(), row.getFirstName(), row.getLastName(),
                StudentStatus.parse(row.getStatus()) == StudentStatus.ACTIVE);
    }
}
