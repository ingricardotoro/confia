package com.confia.students.infrastructure;

import static confia.generated.jooq.tables.StudentsStudent.STUDENTS_STUDENT;
import static confia.generated.jooq.tables.StudentsStudentCodeCounter.STUDENTS_STUDENT_CODE_COUNTER;

import com.confia.kernel.InstitutionId;
import com.confia.students.application.IndexedNationalId;
import com.confia.students.application.NationalIdStorage;
import com.confia.students.application.Page;
import com.confia.students.application.PageRequest;
import com.confia.students.application.ProtectedNationalId;
import com.confia.students.application.StudentFilter;
import com.confia.students.application.StudentRepository;
import com.confia.students.domain.DuplicateNationalIdException;
import com.confia.students.domain.ResourceNotFoundException;
import com.confia.students.domain.Sex;
import com.confia.students.domain.Student;
import com.confia.students.domain.StudentStatus;
import confia.generated.jooq.tables.records.StudentsStudentRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link StudentRepository} over {@code students_student} and its code counter. It
 * never selects the encrypted document nor its blind index into a domain object: reading a student
 * yields only whether there is one, and the two columns are written on insert and on a replacement or removal.
 */
public final class JooqStudentRepository implements StudentRepository {

    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "studentCode", STUDENTS_STUDENT.STUDENT_CODE,
            "lastName", STUDENTS_STUDENT.LAST_NAME,
            "firstName", STUDENTS_STUDENT.FIRST_NAME,
            "birthDate", STUDENTS_STUDENT.BIRTH_DATE);

    private final DSLContext dsl;

    public JooqStudentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Student> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(STUDENTS_STUDENT)
                .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_STUDENT.ID.eq(id))
                .fetchOptional().map(JooqStudentRepository::toDomain);
    }

    @Override
    public long nextCodeSequence(InstitutionId institutionId) {
        // One statement: the first allocation inserts the row, every other updates it, and the row lock
        // taken by the update lasts until the end of the transaction, so concurrent creations queue here.
        return dsl.insertInto(STUDENTS_STUDENT_CODE_COUNTER)
                .set(STUDENTS_STUDENT_CODE_COUNTER.INSTITUTION_ID, institutionId.value())
                .set(STUDENTS_STUDENT_CODE_COUNTER.LAST_VALUE, 1L)
                .onConflict(STUDENTS_STUDENT_CODE_COUNTER.INSTITUTION_ID)
                .doUpdate()
                .set(STUDENTS_STUDENT_CODE_COUNTER.LAST_VALUE, STUDENTS_STUDENT_CODE_COUNTER.LAST_VALUE.plus(1L))
                .set(STUDENTS_STUDENT_CODE_COUNTER.UPDATED_AT, DSL.currentOffsetDateTime())
                .returningResult(STUDENTS_STUDENT_CODE_COUNTER.LAST_VALUE)
                .fetchSingle()
                .value1();
    }

    @Override
    public boolean codeExists(InstitutionId institutionId, String code) {
        return dsl.fetchExists(STUDENTS_STUDENT,
                STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()).and(STUDENTS_STUDENT.STUDENT_CODE.eq(code)));
    }

    @Override
    public void insert(InstitutionId institutionId, Student student, ProtectedNationalId document) {
        insertRow(institutionId, student, document, null);
    }

    @Override
    public void insertImported(InstitutionId institutionId, Student student, ProtectedNationalId document,
            String externalRef) {
        insertRow(institutionId, student, document, externalRef);
    }

    @Override
    public Set<String> existingExternalRefs(InstitutionId institutionId, Collection<String> externalRefs) {
        if (externalRefs.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.select(STUDENTS_STUDENT.EXTERNAL_REF).from(STUDENTS_STUDENT)
                .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_STUDENT.EXTERNAL_REF.in(externalRefs))
                .fetch(STUDENTS_STUDENT.EXTERNAL_REF));
    }

    @Override
    public Set<String> existingBlindIndexes(InstitutionId institutionId, Collection<String> blindIndexes) {
        if (blindIndexes.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.select(STUDENTS_STUDENT.NATIONAL_ID_BLIND_INDEX).from(STUDENTS_STUDENT)
                .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_STUDENT.NATIONAL_ID_BLIND_INDEX.in(blindIndexes))
                .fetch(STUDENTS_STUDENT.NATIONAL_ID_BLIND_INDEX));
    }

    private void insertRow(InstitutionId institutionId, Student student, ProtectedNationalId document,
            String externalRef) {
        refuseDocumentOfAnotherStudent(institutionId, student.id(), document);
        StudentWrites.translating(() -> dsl.insertInto(STUDENTS_STUDENT)
                .set(STUDENTS_STUDENT.INSTITUTION_ID, institutionId.value())
                .set(STUDENTS_STUDENT.ID, student.id())
                .set(STUDENTS_STUDENT.STUDENT_CODE, student.studentCode())
                .set(STUDENTS_STUDENT.FIRST_NAME, student.firstName())
                .set(STUDENTS_STUDENT.LAST_NAME, student.lastName())
                .set(STUDENTS_STUDENT.BIRTH_DATE, student.birthDate())
                .set(STUDENTS_STUDENT.SEX, student.sex().name())
                .set(STUDENTS_STUDENT.EXTERNAL_REF, externalRef)
                .set(STUDENTS_STUDENT.NATIONAL_ID_ENCRYPTED, document == null ? null : document.encrypted())
                .set(STUDENTS_STUDENT.NATIONAL_ID_BLIND_INDEX, document == null ? null : document.blindIndex())
                .set(STUDENTS_STUDENT.STATUS, student.status().name())
                .set(STUDENTS_STUDENT.CREATED_AT, StudentWrites.toDatabase(student.createdAt()))
                .set(STUDENTS_STUDENT.UPDATED_AT, StudentWrites.toDatabase(student.updatedAt()))
                .execute());
    }

    @Override
    public void update(InstitutionId institutionId, Student student, NationalIdStorage storage) {
        if (storage instanceof NationalIdStorage.Store store) {
            refuseDocumentOfAnotherStudent(institutionId, student.id(), store.document());
        }
        StudentWrites.translating(() -> {
            var update = dsl.update(STUDENTS_STUDENT)
                    .set(STUDENTS_STUDENT.FIRST_NAME, student.firstName())
                    .set(STUDENTS_STUDENT.LAST_NAME, student.lastName())
                    .set(STUDENTS_STUDENT.BIRTH_DATE, student.birthDate())
                    .set(STUDENTS_STUDENT.SEX, student.sex().name())
                    .set(STUDENTS_STUDENT.STATUS, student.status().name())
                    .set(STUDENTS_STUDENT.UPDATED_AT, StudentWrites.toDatabase(student.updatedAt()));
            var withDocument = switch (storage) {
                case NationalIdStorage.Keep keep -> update;
                case NationalIdStorage.Clear clear -> update
                        .set(STUDENTS_STUDENT.NATIONAL_ID_ENCRYPTED, (String) null)
                        .set(STUDENTS_STUDENT.NATIONAL_ID_BLIND_INDEX, (String) null);
                case NationalIdStorage.Store store -> update
                        .set(STUDENTS_STUDENT.NATIONAL_ID_ENCRYPTED, store.document().encrypted())
                        .set(STUDENTS_STUDENT.NATIONAL_ID_BLIND_INDEX, store.document().blindIndex());
            };
            int rows = withDocument
                    .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                    .and(STUDENTS_STUDENT.ID.eq(student.id()))
                    .execute();
            if (rows == 0) {
                throw new ResourceNotFoundException();
            }
        });
    }

    /**
     * During a key rotation a row may still carry the index of the previous version, which the unique index
     * cannot equate with the new one, so the duplicate is searched under every configured version first. The
     * unique index stays the last word for two concurrent writers of the same version.
     */
    private void refuseDocumentOfAnotherStudent(InstitutionId institutionId, UUID studentId,
            ProtectedNationalId document) {
        if (document != null && document.lookupIndexes().size() > 1 && dsl.fetchExists(STUDENTS_STUDENT,
                STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value())
                        .and(STUDENTS_STUDENT.ID.ne(studentId))
                        .and(STUDENTS_STUDENT.NATIONAL_ID_BLIND_INDEX.in(document.lookupIndexes())))) {
            throw new DuplicateNationalIdException();
        }
    }

    @Override
    public List<IndexedNationalId> lockNationalIdsNotIndexedWith(InstitutionId institutionId, String version,
            int limit) {
        return dsl.select(STUDENTS_STUDENT.ID, STUDENTS_STUDENT.NATIONAL_ID_ENCRYPTED)
                .from(STUDENTS_STUDENT)
                .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_STUDENT.NATIONAL_ID_BLIND_INDEX.isNotNull())
                .and(STUDENTS_STUDENT.NATIONAL_ID_BLIND_INDEX.notLike(version + ":%"))
                .orderBy(STUDENTS_STUDENT.ID.asc())
                .limit(limit)
                .forUpdate()
                .fetch(row -> new IndexedNationalId(row.value1(), row.value2()));
    }

    @Override
    public void replaceBlindIndex(InstitutionId institutionId, UUID studentId, String blindIndex) {
        StudentWrites.translating(() -> {
            int rows = dsl.update(STUDENTS_STUDENT)
                    .set(STUDENTS_STUDENT.NATIONAL_ID_BLIND_INDEX, blindIndex)
                    .where(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                    .and(STUDENTS_STUDENT.ID.eq(studentId))
                    .and(STUDENTS_STUDENT.NATIONAL_ID_ENCRYPTED.isNotNull())
                    .execute();
            if (rows == 0) {
                throw new ResourceNotFoundException();
            }
        });
    }

    @Override
    public Page<Student> list(InstitutionId institutionId, PageRequest request, StudentFilter filter) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(STUDENTS_STUDENT.INSTITUTION_ID.eq(institutionId.value()));
        if (filter.status() != null) {
            conditions.add(STUDENTS_STUDENT.STATUS.eq(filter.status().name()));
        }
        if (filter.text() != null) {
            // containsIgnoreCase binds the text and escapes the wildcards of LIKE inside it.
            conditions.add(DSL.or(STUDENTS_STUDENT.FIRST_NAME.containsIgnoreCase(filter.text()),
                    STUDENTS_STUDENT.LAST_NAME.containsIgnoreCase(filter.text()),
                    STUDENTS_STUDENT.STUDENT_CODE.containsIgnoreCase(filter.text())));
        }
        Condition scope = DSL.and(conditions);
        long total = dsl.fetchCount(STUDENTS_STUDENT, scope);
        List<Student> rows = dsl.selectFrom(STUDENTS_STUDENT).where(scope)
                .orderBy(order(request), STUDENTS_STUDENT.STUDENT_CODE.asc(), STUDENTS_STUDENT.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqStudentRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(PageRequest request) {
        Field<?> field = SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static Student toDomain(StudentsStudentRecord row) {
        OffsetDateTime created = row.getCreatedAt();
        OffsetDateTime updated = row.getUpdatedAt();
        return new Student(row.getId(), row.getStudentCode(), row.getFirstName(), row.getLastName(),
                row.getBirthDate(), Sex.parse(row.getSex()), StudentStatus.parse(row.getStatus()),
                row.getNationalIdEncrypted() != null, StudentWrites.fromDatabase(created), StudentWrites.fromDatabase(updated));
    }
}
