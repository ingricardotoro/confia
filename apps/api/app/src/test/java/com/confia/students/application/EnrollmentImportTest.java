package com.confia.students.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.SectionRef;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import com.confia.students.application.importing.EnrollmentImportRow;
import com.confia.students.application.importing.EnrollmentOutcome;
import com.confia.students.application.importing.PartialEnrollmentImportException;
import com.confia.students.application.importing.RowProblem;
import com.confia.students.domain.Enrollment;
import com.confia.students.domain.EnrollmentStatus;
import com.confia.students.domain.Sex;
import com.confia.students.domain.Student;
import com.confia.students.domain.StudentStatus;
import com.confia.support.JdbcStubTransactions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The bulk import of enrollments over in-memory ports (docs/cortes/imports-enrollments.md): the judgment that
 * anticipates the rules, and the application through the real {@link ManageEnrollments}, so the capacity, the year, the
 * section, the student and the dates are decided by the same use case as the hand enrollment. The lock of the section
 * and the simultaneous applications are PostgreSQL's and are proven in {@code ImportEnrollmentsEndpointsIT}.
 */
class EnrollmentImportTest {

    private static final Instant NOW = Instant.parse("2026-03-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final LocalDate YEAR_START = LocalDate.of(2026, 1, 15);
    private static final LocalDate YEAR_END = LocalDate.of(2026, 11, 30);
    private static final String ENROLLED = "2026-02-01";

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final InMemoryStudents studentStore = new InMemoryStudents();
    private final InMemoryEnrollments world = new InMemoryEnrollments(studentStore);
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls);
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = actorOf(institutionUuid);
    private final String requestId = UUID.randomUUID().toString();
    private final List<Integer> lookupSizes = new ArrayList<>();
    private final StudentDirectory directory = new Directory();

    private final UUID yearId = UUID.randomUUID();
    private final UUID grade7 = UUID.randomUUID();
    private final UUID grade8 = UUID.randomUUID();
    private final SectionRef sectionA = section("A", true, 2, yearId, "ACTIVE", grade7, "7mo", "Basica");
    private final SectionRef sectionB = section("B", true, null, yearId, "ACTIVE", grade7, "7mo", "Basica");
    private final SectionRef sectionC = section("C", true, 1, yearId, "ACTIVE", grade8, "8vo", "Basica");
    private final SectionRef inactive = section("I", false, 5, yearId, "ACTIVE", grade7, "7mo", "Basica");
    private final SectionRef closed = section("Z", true, 5, UUID.randomUUID(), "CLOSED", grade7, "7mo", "Basica");

    {
        for (SectionRef ref : List.of(sectionA, sectionB, sectionC, inactive, closed)) {
            world.arrange(institution, ref);
        }
    }

    private final ManageEnrollments manage = new ManageEnrollments(runner, world.enrollments, studentStore.students,
            world.sections, auditWriter, Clock.fixed(NOW, ZoneOffset.UTC));
    private final EnrollmentImport imports = importing(world.enrollments, NOW);

    private EnrollmentImport importing(EnrollmentRepository enrollments, Instant at) {
        return new EnrollmentImport(runner, directory, world.sections, enrollments, manage,
                Clock.fixed(at, ZoneOffset.UTC));
    }

    private static AuthenticatedActor actorOf(UUID institutionId) {
        return new AuthenticatedActor(UUID.randomUUID(), institutionId, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private static SectionRef section(String name, boolean active, Integer capacity, UUID year, String status,
            UUID grade, String gradeName, String modality) {
        return new SectionRef(UUID.randomUUID(), name, active, capacity, year, "2026", status, YEAR_START, YEAR_END,
                grade, gradeName, modality);
    }

    private Student student(String reference, StudentStatus status) {
        return studentIn(institution, reference, status);
    }

    private Student studentIn(InstitutionId owner, String reference, StudentStatus status) {
        Student student = new Student(UUID.randomUUID(), "E-" + UUID.randomUUID().toString().substring(0, 8), "Ana",
                "Perez", LocalDate.of(2012, 5, 20), Sex.F, status, false, NOW, NOW);
        studentStore.arrange(owner, student, null);
        studentStore.externalRefs.put(new InMemoryStudents.Key(owner.value(), student.id()), reference);
        return student;
    }

    private Student student(String reference) {
        return student(reference, StudentStatus.ACTIVE);
    }

    private static EnrollmentImportRow row(int line, String reference, String section, String on) {
        return new EnrollmentImportRow(line, reference, "2026", "Basica", "7mo", section, on);
    }

    private static EnrollmentImportRow row(int line, String reference, String section) {
        return row(line, reference, section, ENROLLED);
    }

    private List<RowProblem> evaluate(EnrollmentImportRow... rows) {
        return imports.evaluate(actor, requestId, List.of(rows));
    }

    private List<EnrollmentOutcome> apply(EnrollmentImportRow... rows) {
        return imports.applyAll(actor, requestId, List.of(rows));
    }

    private Enrollment activeOf(Student student) {
        return world.rowsOf(institution).stream().filter(e -> e.studentId().equals(student.id()))
                .filter(e -> e.status() == EnrollmentStatus.ACTIVE).findFirst().orElseThrow();
    }

    private void seat(Student student, SectionRef section) {
        manage.enroll(actor, requestId, student.id(), section.sectionId(), LocalDate.parse(ENROLLED));
        audit.clear();
        world.events.clear();
    }

    private final class Directory implements StudentDirectory {

        @Override
        public Optional<StudentRef> find(InstitutionId owner, UUID studentId) {
            return Optional.ofNullable(studentStore.rows.get(new InMemoryStudents.Key(owner.value(), studentId)))
                    .map(stored -> refOf(stored.student()));
        }

        @Override
        public Optional<StudentRef> lock(InstitutionId owner, UUID studentId) {
            return find(owner, studentId);
        }

        @Override
        public Map<UUID, StudentRef> findAll(InstitutionId owner, Collection<UUID> studentIds) {
            Map<UUID, StudentRef> found = new HashMap<>();
            studentIds.forEach(id -> find(owner, id).ifPresent(ref -> found.put(id, ref)));
            return found;
        }

        @Override
        public Map<String, StudentRef> findByExternalRefs(InstitutionId owner, Collection<String> references) {
            lookupSizes.add(references.size());
            Map<String, StudentRef> found = new HashMap<>();
            studentStore.externalRefs.forEach((key, reference) -> {
                if (key.institution().equals(owner.value()) && references.contains(reference)) {
                    found.put(reference, refOf(studentStore.rows.get(key).student()));
                }
            });
            return found;
        }

        private StudentRef refOf(Student student) {
            return new StudentRef(student.id(), student.studentCode(), student.firstName(), student.lastName(),
                    student.status() == StudentStatus.ACTIVE);
        }
    }

    // --- evaluate: the judgment delegates to the rules and writes nothing ---

    @Test
    void validRowsHaveNoProblemsAndNothingIsWrittenNorLocked() {
        student("L1");
        student("L2");

        assertThat(evaluate(row(2, "L1", "A"), row(3, "L2", "B"))).isEmpty();

        assertThat(world.rowsOf(institution)).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(world.events).doesNotContain("insert", "lock-section", "insert-transfer");
        assertThat(transactionCalls).doesNotContain("rollback");
    }

    @Test
    void anUnknownStudentAndAnUnknownSectionAreReportedOnTheirLineAndColumn() {
        student("L1");

        assertThat(evaluate(row(2, "NOPE", "A"), row(3, "L1", "NO-SUCH"))).containsExactly(
                new RowProblem(2, "studentLegacyId", "unknown-student"), new RowProblem(3, "section", "unknown-section"));
    }

    @Test
    void theSectionIsFoundByItsFourNamesWithoutCaseBorderOrRepeatedSpaces() {
        student("L1");
        student("L2");
        EnrollmentImportRow spelled = new EnrollmentImportRow(2, " L1 ", " 2026 ", "  BASICA", " 7MO  ", " a ", ENROLLED);
        EnrollmentImportRow other = new EnrollmentImportRow(3, "L2", "2026", "Basica", "7mo", "NO SUCH", ENROLLED);

        assertThat(imports.evaluate(actor, requestId, List.of(spelled, other)))
                .containsExactly(new RowProblem(3, "section", "unknown-section"));
    }

    @Test
    void aNameThatMatchesTwoSectionsIsAmbiguousOnBothTheEvaluationAndNeverGuessed() {
        student("L1");
        world.arrange(institution, section("A", true, 5, yearId, "ACTIVE", UUID.randomUUID(), "7mo", "Basica"));

        assertThat(evaluate(row(2, "L1", "A"))).containsExactly(new RowProblem(2, "section", "ambiguous-section"));
    }

    @Test
    void aStudentRepeatedInTheFileInTheSameYearIsDuplicateInFileOnTheLaterRowsOnly() {
        student("L1");

        assertThat(evaluate(row(2, "L1", "A"), row(3, "L1", "B"), row(4, " L1 ", "A"))).containsExactly(
                new RowProblem(3, "studentLegacyId", "duplicate-in-file"),
                new RowProblem(4, "studentLegacyId", "duplicate-in-file"));
    }

    @Test
    void theEstimatedCapacityCountsTheEnrollmentsThatExistPlusTheRowsOfTheFileInOrder() {
        Student first = student("L1");
        student("L2");
        student("L3");
        seat(first, sectionA);

        assertThat(evaluate(row(2, "L2", "A"), row(3, "L3", "A"))).containsExactly(
                new RowProblem(3, "section", "section-full"));
    }

    @Test
    void anExactCapacityFitsAndTheNextRowDoesNot() {
        student("L1");
        student("L2");
        student("L3");

        assertThat(evaluate(row(2, "L1", "A"), row(3, "L2", "A"))).isEmpty();
        assertThat(evaluate(row(2, "L1", "A"), row(3, "L2", "A"), row(4, "L3", "A")))
                .containsExactly(new RowProblem(4, "section", "section-full"));
    }

    @Test
    void aSectionWithoutCapacityNeverFills() {
        List<EnrollmentImportRow> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            student("L" + i);
            rows.add(row(i + 2, "L" + i, "B"));
        }

        assertThat(imports.evaluate(actor, requestId, rows)).isEmpty();
    }

    @Test
    void anInactiveStudentAnInactiveSectionAndAClosedYearAreReported() {
        student("L1", StudentStatus.INACTIVE);
        student("L2");
        student("L3");

        assertThat(evaluate(row(2, "L1", "A"), row(3, "L2", "I"), row(4, "L3", "Z"))).containsExactly(
                new RowProblem(2, "studentLegacyId", "student-not-active"),
                new RowProblem(3, "section", "inactive-reference"),
                new RowProblem(4, "academicYear", "academic-year-closed"));
    }

    @Test
    void theDateMustFallInsideTheYearAndNotBeInTheFutureWithBothBordersAndTheirNeighbors() {
        for (int i = 1; i <= 7; i++) {
            student("L" + i);
        }

        assertThat(evaluate(row(2, "L1", "B", "2026-01-14"), row(3, "L2", "B", "2026-01-15"),
                row(4, "L3", "B", "2026-03-10"), row(5, "L4", "B", "2026-03-11"), row(6, "L5", "B", "2099-01-01"),
                row(7, "L6", "B", "2026-02-30"), row(8, "L7", "B", "10/03/2026"))).containsExactly(
                new RowProblem(2, "enrolledOn", "enrollment-date-out-of-range"),
                new RowProblem(5, "enrolledOn", "enrollment-date-out-of-range"),
                new RowProblem(6, "enrolledOn", "enrollment-date-out-of-range"),
                new RowProblem(7, "enrolledOn", "invalid-date"), new RowProblem(8, "enrolledOn", "invalid-format"));
    }

    @Test
    void theLastDayOfTheYearIsAcceptedOnceItHasPassedAndTheDayAfterIsNot() {
        student("L1");
        student("L2");
        EnrollmentImport later = importing(world.enrollments, Instant.parse("2026-12-15T15:00:00Z"));

        assertThat(later.evaluate(actor, requestId, List.of(row(2, "L1", "B", "2026-11-30"),
                row(3, "L2", "B", "2026-12-01")))).containsExactly(
                new RowProblem(3, "enrolledOn", "enrollment-date-out-of-range"));
    }

    @Test
    void aMissingCellIsRequiredAndTheRestOfTheRowIsNotJudged() {
        student("L1");

        assertThat(evaluate(new EnrollmentImportRow(2, "L1", "2026", "Basica", "7mo", "", null),
                new EnrollmentImportRow(3, null, "2026", "Basica", "7mo", "A", ENROLLED))).containsExactly(
                new RowProblem(2, "section", "required"), new RowProblem(2, "enrolledOn", "required"),
                new RowProblem(3, "studentLegacyId", "required"));
    }

    @Test
    void anEnrollmentTheStudentAlreadyHoldsIsAlreadyImportedInTheSameSectionAndDuplicateActiveInAnother() {
        Student first = student("L1");
        Student second = student("L2");
        seat(first, sectionA);
        seat(second, sectionA);

        assertThat(evaluate(row(2, "L1", "A"), row(3, "L2", "B"))).containsExactly(
                new RowProblem(2, "studentLegacyId", "already-imported"),
                new RowProblem(3, "studentLegacyId", "duplicate-active-enrollment"));
    }

    @Test
    void theStudentsAndSectionsOfAnotherInstitutionAreInvisible() {
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        studentIn(other, "FOREIGN", StudentStatus.ACTIVE);
        world.arrange(other, section("OTHER", true, 5, yearId, "ACTIVE", grade7, "7mo", "Basica"));
        student("L1");

        assertThat(evaluate(row(2, "FOREIGN", "A"), row(3, "L1", "OTHER"))).containsExactly(
                new RowProblem(2, "studentLegacyId", "unknown-student"), new RowProblem(3, "section", "unknown-section"));
    }

    @Test
    void anEmptyListHasNoProblemsNoOutcomesAndTheLookupsGoInChunks() {
        assertThat(imports.evaluate(actor, requestId, List.of())).isEmpty();
        assertThat(imports.applyAll(actor, requestId, List.of())).isEmpty();

        List<EnrollmentImportRow> many = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            many.add(row(i + 2, "NOPE" + i, "A"));
        }
        assertThat(imports.evaluate(actor, requestId, many)).hasSize(2500);
        assertThat(lookupSizes).contains(EnrollmentImport.LOOKUP_CHUNK, EnrollmentImport.LOOKUP_CHUNK, 500)
                .allMatch(size -> size <= EnrollmentImport.LOOKUP_CHUNK);
    }

    @Test
    void theProblemsNeverCarryAValue() {
        student("SECRET-REF");

        List<RowProblem> problems = evaluate(
                new EnrollmentImportRow(2, "SECRET-REF", "2026", "Basica", "7mo", "SECRET-SECTION", "2099-01-01"),
                new EnrollmentImportRow(3, "SECRET-OTHER", "2026", "Basica", "7mo", "A", ENROLLED),
                new EnrollmentImportRow(4, "SECRET-REF", "2026", "Basica", "7mo", "A", "2099-01-01"));

        assertThat(problems).isNotEmpty();
        assertThat(problems.toString()).doesNotContain("SECRET").doesNotContain("2099").doesNotContain("Basica");
    }

    @Test
    void theArgumentsAreRequired() {
        assertThatThrownBy(() -> imports.evaluate(null, requestId, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> imports.evaluate(actor, null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> imports.evaluate(actor, requestId, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> imports.applyAll(null, requestId, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> imports.applyAll(actor, null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> imports.applyAll(actor, requestId, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnrollmentImport(null, directory, world.sections, world.enrollments, manage,
                Clock.systemUTC())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnrollmentImport(runner, null, world.sections, world.enrollments, manage,
                Clock.systemUTC())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnrollmentImport(runner, directory, null, world.enrollments, manage,
                Clock.systemUTC())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnrollmentImport(runner, directory, world.sections, null, manage,
                Clock.systemUTC())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnrollmentImport(runner, directory, world.sections, world.enrollments, null,
                Clock.systemUTC())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnrollmentImport(runner, directory, world.sections, world.enrollments, manage,
                null)).isInstanceOf(NullPointerException.class);
    }

    // --- applyAll: the real use case decides ---

    @Test
    void theRowsAreEnrolledInFileOrderAndTheRealCapacityRejectsTheLaterOnesWithSectionFull() {
        Student first = student("L1");
        Student second = student("L2");
        Student third = student("L3");

        List<EnrollmentOutcome> outcomes = apply(row(2, "L1", "A"), row(3, "L2", "A"), row(4, "L3", "A"));

        assertThat(outcomes).containsExactly(EnrollmentOutcome.CREATED, EnrollmentOutcome.CREATED,
                EnrollmentOutcome.SECTION_FULL);
        assertThat(world.rowsOf(institution)).extracting(Enrollment::studentId)
                .containsExactly(first.id(), second.id());
        assertThat(world.rowsOf(institution)).extracting(Enrollment::sectionId).containsOnly(sectionA.sectionId());
        assertThat(world.rowsOf(institution)).extracting(Enrollment::enrolledOn).containsOnly(LocalDate.parse(ENROLLED));
        assertThat(activeCountOf(third)).isZero();
    }

    private long activeCountOf(Student student) {
        return world.rowsOf(institution).stream().filter(e -> e.studentId().equals(student.id())).count();
    }

    @Test
    void everyRowIsItsOwnTransactionAndTheSectionIsLockedBeforeItsSeatsAreCountedAndTheRowWritten() {
        student("L1");
        student("L2");
        world.events.clear();

        apply(row(2, "L1", "A"), row(3, "L2", "B"));

        assertThat(world.events).containsSubsequence("lock-section", "count", "insert", "lock-section", "count",
                "insert");
        assertThat(transactionCalls.stream().filter("commit"::equals).count()).isGreaterThanOrEqualTo(2);
        assertThat(transactionCalls).doesNotContain("rollback");
    }

    @Test
    void theEnrollmentsCreatedAuditLikeTheHandOnesWithoutAnyCellOfTheFile() {
        Student first = student("SECRET-REF-1");
        Student hand = student("SECRET-REF-2");

        apply(row(2, "SECRET-REF-1", "B"));
        manage.enroll(actor, requestId, hand.id(), sectionB.sectionId(), LocalDate.parse(ENROLLED));

        assertThat(audit).hasSize(2);
        assertThat(audit.getFirst().action()).isEqualTo(audit.get(1).action());
        assertThat(audit.getFirst().afterValue()).doesNotContain("SECRET").doesNotContain("Basica");
        assertThat(audit.getFirst().toString() + audit.getFirst().afterValue()).doesNotContain("SECRET-REF");
        assertThat(activeOf(first).sectionId()).isEqualTo(sectionB.sectionId());
    }

    @Test
    void applyingTheSameRowsAgainSkipsEveryOneAndCreatesNothingMore() {
        student("L1");
        student("L2");
        apply(row(2, "L1", "A"), row(3, "L2", "A"));
        audit.clear();
        int rows = world.rowsOf(institution).size();

        List<EnrollmentOutcome> again = apply(row(2, "L1", "A"), row(3, "L2", "A"));

        assertThat(again).containsExactly(EnrollmentOutcome.ALREADY_IMPORTED, EnrollmentOutcome.ALREADY_IMPORTED);
        assertThat(world.rowsOf(institution)).hasSize(rows);
        assertThat(audit).isEmpty();
    }

    @Test
    void aStudentEnrolledInAnotherSectionIsDuplicateActiveAndTheExistingEnrollmentIsNeverMoved() {
        Student ana = student("L1");
        seat(ana, sectionA);
        Enrollment before = activeOf(ana);

        List<EnrollmentOutcome> outcomes = apply(row(2, "L1", "B"));

        assertThat(outcomes).containsExactly(EnrollmentOutcome.DUPLICATE_ACTIVE_ENROLLMENT);
        assertThat(world.rowsOf(institution)).containsExactly(before);
        assertThat(activeOf(ana).sectionId()).isEqualTo(sectionA.sectionId());
        assertThat(world.history).isEmpty();
        assertThat(world.events).doesNotContain("insert", "update", "insert-transfer", "lock-section");
        assertThat(audit).isEmpty();
    }

    @Test
    void anEnrollmentThatAppearsAfterTheFactsWereReadIsAlreadyImportedInTheSameSectionAndDuplicateInAnother() {
        Student first = student("L1");
        Student second = student("L2");
        AtomicBoolean stale = new AtomicBoolean(true);
        EnrollmentRepository staleFacts = (EnrollmentRepository) Proxy.newProxyInstance(
                EnrollmentRepository.class.getClassLoader(), new Class<?>[] {EnrollmentRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("activeSeatsOf") && stale.getAndSet(false)) {
                        seatBehindTheBack(first, sectionA);
                        seatBehindTheBack(second, sectionA);
                        return List.of();
                    }
                    try {
                        return method.invoke(world.enrollments, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        EnrollmentImport racing = importing(staleFacts, NOW);

        List<EnrollmentOutcome> outcomes = racing.applyAll(actor, requestId,
                List.of(row(2, "L1", "A"), row(3, "L2", "B")));

        assertThat(outcomes).containsExactly(EnrollmentOutcome.ALREADY_IMPORTED,
                EnrollmentOutcome.DUPLICATE_ACTIVE_ENROLLMENT);
        assertThat(world.rowsOf(institution)).hasSize(2).extracting(Enrollment::sectionId)
                .containsOnly(sectionA.sectionId());
    }

    private void seatBehindTheBack(Student student, SectionRef section) {
        world.arrange(institution, new Enrollment(UUID.randomUUID(), student.id(), section.sectionId(), yearId,
                LocalDate.parse(ENROLLED), EnrollmentStatus.ACTIVE, null, null, NOW, NOW));
    }

    @Test
    void aClosedYearAnInactiveSectionAnInactiveStudentAndADateOutsideTheYearAreOutcomesNotErrors() {
        student("L1");
        student("L2", StudentStatus.INACTIVE);
        student("L3");
        student("L4");
        student("L5");

        List<EnrollmentOutcome> outcomes = apply(row(2, "L1", "Z"), row(3, "L2", "A"), row(4, "L3", "I"),
                row(5, "L4", "A", "2026-01-14"), row(6, "L5", "A", "2026-03-11"));

        assertThat(outcomes).containsExactly(EnrollmentOutcome.ACADEMIC_YEAR_CLOSED,
                EnrollmentOutcome.STUDENT_NOT_ACTIVE, EnrollmentOutcome.INACTIVE_SECTION,
                EnrollmentOutcome.DATE_OUT_OF_RANGE, EnrollmentOutcome.DATE_OUT_OF_RANGE);
        assertThat(world.rowsOf(institution)).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void aRejectedRowDoesNotAffectTheOthers() {
        student("L1", StudentStatus.INACTIVE);
        student("L2");

        assertThat(apply(row(2, "L1", "B"), row(3, "L2", "B"))).containsExactly(
                EnrollmentOutcome.STUDENT_NOT_ACTIVE, EnrollmentOutcome.CREATED);
        assertThat(world.rowsOf(institution)).hasSize(1);
    }

    @Test
    void aFullSectionRejectsTheRowsOfALaterApplication() {
        student("L1");
        student("L2");

        assertThat(apply(row(2, "L1", "A"), row(3, "L2", "A"))).containsOnly(EnrollmentOutcome.CREATED);
        student("L3");
        assertThat(apply(row(2, "L3", "A"))).containsExactly(EnrollmentOutcome.SECTION_FULL);
        assertThat(world.rowsOf(institution)).hasSize(2);
    }

    @Test
    void anUnexpectedFailureAfterSomeRowsReportsHowManyWereCreatedAndKeepsTheCause() {
        student("L1");
        student("L2");

        assertThatThrownBy(() -> apply(row(2, "L1", "B"), row(3, "L2", "B"), row(4, "SECRET-GONE", "B")))
                .isInstanceOfSatisfying(PartialEnrollmentImportException.class, e -> {
                    assertThat(e.created()).isEqualTo(2);
                    assertThat(e.failure()).isInstanceOf(IllegalStateException.class);
                    assertThat(e.getMessage()).doesNotContain("SECRET").doesNotContain("L1");
                    assertThat(e.toString()).doesNotContain("SECRET");
                    assertThat(e.failure().getMessage()).doesNotContain("SECRET");
                    assertThat(e.getCause().toString()).doesNotContain("SECRET");
                });
        assertThat(world.rowsOf(institution)).as("the committed rows stay").hasSize(2);
    }

    @Test
    void anUnexpectedFailureBeforeAnyRowWasCreatedPropagatesAsItIs() {
        student("L1");

        assertThatThrownBy(() -> apply(row(2, "SECRET-GONE", "B"), row(3, "L1", "B")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("SECRET")
                .isNotInstanceOf(PartialEnrollmentImportException.class);
        assertThat(world.rowsOf(institution)).isEmpty();
    }

    @Test
    void aSectionNameThatBecameAmbiguousBeforeTheApplicationIsNotGuessed() {
        student("L1");
        world.arrange(institution, section("B", true, 5, yearId, "ACTIVE", UUID.randomUUID(), "7mo", "Basica"));

        assertThatThrownBy(() -> apply(row(2, "L1", "B"))).isInstanceOf(IllegalStateException.class);
        assertThat(world.rowsOf(institution)).isEmpty();
    }
}
