package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.domain.InvalidStudentException;
import com.confia.students.domain.NationalId;
import com.confia.students.domain.NationalIdChange;
import com.confia.students.domain.ResourceNotFoundException;
import com.confia.students.domain.Student;
import com.confia.students.domain.StudentCode;
import com.confia.students.domain.StudentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Registers, edits, reads and lists the students of the institution of the actor. Nothing is deleted: a
 * student is made inactive with {@link #update}. The code and the national identity document are unique in
 * the institution, which the database enforces (the document through its blind index).
 *
 * <p>The code is typed or, when it is missing or blank, generated from the counter of the institution as
 * E-000001, E-000002... taken under the lock the repository holds until the transaction ends; a generated
 * code that a typed one already uses is skipped. The document is protected (encrypted and indexed) before
 * it is stored and never comes back out: views and audit entries carry only whether there is one and its
 * last four characters. On an edit, an omitted document is kept, a blank one removes it and any other
 * replaces it.
 */
public final class ManageStudents {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("studentCode", "lastName", "firstName", "birthDate");

    static final int MIN_SEARCH_LENGTH = 2;

    private final TransactionRunner transactionRunner;
    private final StudentRepository students;
    private final NationalIdProtector protector;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageStudents(TransactionRunner transactionRunner, StudentRepository students,
            NationalIdProtector protector, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.students = Objects.requireNonNull(students, "students");
        this.protector = Objects.requireNonNull(protector, "protector");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StudentView create(AuthenticatedActor actor, String requestId, String studentCode, String firstName,
            String lastName, LocalDate birthDate, String sex, String nationalId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        NationalId document = nationalId == null || nationalId.isBlank() ? null : NationalId.of(nationalId);
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            String code = studentCode == null || studentCode.isBlank() ? generatedCode(institution) : studentCode;
            UUID id = UUID.randomUUID();
            Student student = Student.create(id, code, firstName, lastName, birthDate, sex, document,
                    now());
            ProtectedNationalId protectedDocument =
                    document == null ? null : protector.protect(institution, id, document);
            students.insert(institution, student, protectedDocument);
            auditLogWriter.append(StudentsAudit.created(actor, auditRequestId, student));
            return StudentView.of(student);
        });
    }

    public StudentView update(AuthenticatedActor actor, String requestId, UUID id, String firstName,
            String lastName, LocalDate birthDate, String sex, String status, String nationalId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        NationalIdChange change = NationalIdChange.of(nationalId);
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Student before = students.findById(institution, id).orElseThrow(ResourceNotFoundException::new);
            Student after = before.edit(firstName, lastName, birthDate, sex, status, change, now());
            students.update(institution, after, storageFor(institution, id, change));
            auditLogWriter.append(StudentsAudit.updated(actor, auditRequestId, before, after));
            return StudentView.of(after);
        });
    }

    public StudentView get(AuthenticatedActor actor, String requestId, UUID id) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> students.findById(institution, id).map(StudentView::of)
                        .orElseThrow(ResourceNotFoundException::new));
    }

    /**
     * One page of the students. {@code search} is stripped: blank means no search, one character is
     * refused, two or more look in the first name, the last name and the code.
     */
    public Page<StudentView> list(AuthenticatedActor actor, String requestId, int page, int size, String sort,
            String search, String status) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "lastName", true);
        StudentFilter filter = new StudentFilter(searchText(search),
                status == null ? null : StudentStatus.parse(status));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> students.list(institution, request, filter).map(StudentView::of));
    }

    /** The instant of the write, to the microsecond the column keeps, so an answer equals what a read returns. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private String generatedCode(InstitutionId institution) {
        String code;
        do {
            code = StudentCode.generated(students.nextCodeSequence(institution));
        } while (students.codeExists(institution, code));
        return code;
    }

    private NationalIdStorage storageFor(InstitutionId institution, UUID studentId, NationalIdChange change) {
        return switch (change) {
            case NationalIdChange.Keep keep -> NationalIdStorage.Keep.INSTANCE;
            case NationalIdChange.Remove remove -> NationalIdStorage.Clear.INSTANCE;
            case NationalIdChange.Replace replace ->
                    new NationalIdStorage.Store(protector.protect(institution, studentId, replace.nationalId()));
        };
    }

    private static String searchText(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String stripped = search.strip();
        if (stripped.length() < MIN_SEARCH_LENGTH) {
            throw new InvalidStudentException();
        }
        return stripped;
    }
}
