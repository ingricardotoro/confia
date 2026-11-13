package com.confia.students.infrastructure;

import static confia.generated.jooq.tables.StudentsStudent.STUDENTS_STUDENT;

import com.confia.kernel.InstitutionId;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import com.confia.students.domain.StudentStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record5;
import org.jooq.SelectSelectStep;

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
        return refSelect().from(STUDENTS_STUDENT)
                .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_STUDENT.ID.eq(studentId))
                .fetchOptional().map(JooqStudentDirectory::toRef);
    }

    @Override
    public Optional<StudentRef> lock(InstitutionId institutionId, UUID studentId) {
        return refSelect().from(STUDENTS_STUDENT)
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
        refSelect().from(STUDENTS_STUDENT)
                .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_STUDENT.ID.in(studentIds))
                .fetch().forEach(row -> {
                    StudentRef ref = toRef(row);
                    found.put(ref.studentId(), ref);
                });
        return found;
    }

    @Override
    public Map<String, StudentRef> findByExternalRefs(InstitutionId institutionId, Collection<String> externalRefs) {
        Map<String, StudentRef> found = new HashMap<>();
        if (externalRefs.isEmpty()) {
            return found;
        }
        dsl.select(STUDENTS_STUDENT.EXTERNAL_REF, STUDENTS_STUDENT.ID, STUDENTS_STUDENT.STUDENT_CODE,
                STUDENTS_STUDENT.FIRST_NAME, STUDENTS_STUDENT.LAST_NAME, STUDENTS_STUDENT.STATUS)
                .from(STUDENTS_STUDENT)
                .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_STUDENT.EXTERNAL_REF.in(externalRefs))
                .fetch().forEach(row -> found.put(row.value1(), new StudentRef(row.value2(), row.value3(),
                        row.value4(), row.value5(), StudentStatus.parse(row.value6()) == StudentStatus.ACTIVE)));
        return found;
    }

    /** The only columns a reference needs: never the document, its blind index nor the birth date. */
    private SelectSelectStep<Record5<UUID, String, String, String, String>> refSelect() {
        return dsl.select(STUDENTS_STUDENT.ID, STUDENTS_STUDENT.STUDENT_CODE, STUDENTS_STUDENT.FIRST_NAME,
                STUDENTS_STUDENT.LAST_NAME, STUDENTS_STUDENT.STATUS);
    }

    private static StudentRef toRef(Record5<UUID, String, String, String, String> row) {
        return new StudentRef(row.value1(), row.value2(), row.value3(), row.value4(),
                StudentStatus.parse(row.value5()) == StudentStatus.ACTIVE);
    }
}
