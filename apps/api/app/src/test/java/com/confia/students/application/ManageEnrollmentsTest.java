package com.confia.students.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.DomainException;
import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.SectionRef;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.domain.ClosedAcademicYearException;
import com.confia.students.domain.DuplicateActiveEnrollmentException;
import com.confia.students.domain.Enrollment;
import com.confia.students.domain.EnrollmentStatus;
import com.confia.students.domain.InactiveSectionException;
import com.confia.students.domain.InvalidEnrollmentStateException;
import com.confia.students.domain.ResourceNotFoundException;
import com.confia.students.domain.SectionFullException;
import com.confia.students.domain.Sex;
import com.confia.students.domain.Student;
import com.confia.students.domain.StudentNotActiveException;
import com.confia.students.domain.StudentStatus;
import com.confia.students.domain.TransferYearMismatchException;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rules and edge cases of docs/cortes/enrollments.md through the use case, with the ports in memory and
 * a transaction stub that tells a commit from a rollback. The races (two enrollments for the last seat, two
 * simultaneous exits) are settled by the locks and proven against PostgreSQL in {@code EnrollmentEndpointsIT};
 * here the order in which the use case takes its locks is proven.
 */
class ManageEnrollmentsTest {

    private static final Instant NOW = Instant.parse("2026-03-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final LocalDate YEAR_START = LocalDate.of(2026, 1, 15);
    private static final LocalDate YEAR_END = LocalDate.of(2026, 11, 30);

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<String> readOnlyFlags = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final InMemoryStudents studentStore = new InMemoryStudents();
    private final InMemoryEnrollments world = new InMemoryEnrollments(studentStore);
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls, readOnlyFlags);
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = actorOf(institutionUuid);
    private final String requestId = UUID.randomUUID().toString();
    private final ManageEnrollments enrollments = use(NOW);

    private final UUID yearId = UUID.randomUUID();
    private final UUID otherYearId = UUID.randomUUID();
    private final UUID grade7 = UUID.randomUUID();
    private final UUID grade8 = UUID.randomUUID();
    private final SectionRef sectionA = section("A", true, 2, yearId, "ACTIVE", grade7, "7mo");
    private final SectionRef sectionB = section("B", true, null, yearId, "ACTIVE", grade7, "7mo");
    private final SectionRef sectionC = section("C", true, 1, yearId, "ACTIVE", grade8, "8vo");
    private final SectionRef inactiveSection = section("I", false, 5, yearId, "ACTIVE", grade7, "7mo");
    private final SectionRef nextYearSection = section("A", true, 5, otherYearId, "PLANNED", grade7, "7mo");
    private final SectionRef closedSection = section("Z", true, 5, UUID.randomUUID(), "CLOSED", grade7, "7mo");

    {
        for (SectionRef section : List.of(sectionA, sectionB, sectionC, inactiveSection, nextYearSection,
                closedSection)) {
            world.arrange(institution, section);
        }
        world.activeYears.put(institutionUuid, yearId);
    }

    private ManageEnrollments use(Instant at) {
        return new ManageEnrollments(runner, world.enrollments, studentStore.students, world.sections, auditWriter,
                Clock.fixed(at, ZoneOffset.UTC));
    }

    private static AuthenticatedActor actorOf(UUID institutionId) {
        return new AuthenticatedActor(UUID.randomUUID(), institutionId, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private SectionRef section(String name, boolean active, Integer capacity, UUID year, String status, UUID grade,
            String gradeName) {
        return new SectionRef(UUID.randomUUID(), name, active, capacity, year, "Anio " + status, status,
                YEAR_START, YEAR_END, grade, gradeName, "Basica");
    }

    private Student student(String code, String first, String last, StudentStatus status) {
        Student student = new Student(UUID.randomUUID(), code, first, last, LocalDate.of(2012, 5, 20), Sex.F, status,
                false, NOW, NOW);
        studentStore.arrange(institution, student, null);
        return student;
    }

    private Student student() {
        return student("E-" + UUID.randomUUID().toString().substring(0, 8), "Ana", "Perez", StudentStatus.ACTIVE);
    }

    private EnrollmentView enroll(Student student, SectionRef section, LocalDate on) {
        return enrollments.enroll(actor, requestId, student.id(), section.sectionId(), on);
    }

    private EnrollmentView enroll(Student student, SectionRef section) {
        return enroll(student, section, LocalDate.of(2026, 2, 1));
    }

    private EnrollmentView exit(EnrollmentView view, String status, LocalDate on, String reason) {
        return enrollments.exit(actor, requestId, view.id(), status, on, reason);
    }

    private boolean committed() {
        return transactionCalls.contains("commit") && !transactionCalls.contains("rollback");
    }

    private boolean rolledBack() {
        return transactionCalls.contains("rollback") && !transactionCalls.contains("commit");
    }

    private static JsonNode json(String document) {
        return JsonMapper.builder().build().readTree(document);
    }

    // --- Enrolling ---

    @Test
    void enrollingCreatesAnActiveEnrollmentInTheYearOfTheSectionWithTheNamesOfTheStructure() {
        Student ana = student("E-000001", "Ana", "Perez", StudentStatus.ACTIVE);

        EnrollmentView view = enroll(ana, sectionA);

        assertThat(view.studentId()).isEqualTo(ana.id());
        assertThat(view.studentCode()).isEqualTo("E-000001");
        assertThat(view.studentFirstName()).isEqualTo("Ana");
        assertThat(view.studentLastName()).isEqualTo("Perez");
        assertThat(view.academicYearId()).as("derived from the section, never sent").isEqualTo(yearId);
        assertThat(view.academicYearName()).isEqualTo("Anio ACTIVE");
        assertThat(view.sectionId()).isEqualTo(sectionA.sectionId());
        assertThat(view.sectionName()).isEqualTo("A");
        assertThat(view.gradeId()).isEqualTo(grade7);
        assertThat(view.gradeName()).isEqualTo("7mo");
        assertThat(view.modalityName()).isEqualTo("Basica");
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.enrolledOn()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(view.withdrawnOn()).isNull();
        assertThat(view.transfers()).isEmpty();
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(world.rowsOf(institution)).hasSize(1);
        assertThat(committed()).isTrue();
    }

    @Test
    void theSectionIsLockedBeforeItsSeatsAreCountedAndBeforeTheRowIsWritten() {
        enroll(student(), sectionA);

        assertThat(world.events).containsExactly("lock-section", "count", "insert");
    }

    @Test
    void aStudentOrASectionThatDoesNotExistInTheInstitutionIsNotFoundAndLeavesNothing() {
        Student ana = student();
        InstitutionId stranger = new InstitutionId(UUID.randomUUID());
        Student theirs = new Student(UUID.randomUUID(), "E-999999", "Eva", "Mejia", LocalDate.of(2012, 5, 20),
                Sex.F, StudentStatus.ACTIVE, false, NOW, NOW);
        studentStore.arrange(stranger, theirs, null);
        SectionRef theirSection = section("X", true, 5, yearId, "ACTIVE", grade7, "7mo");
        world.arrange(stranger, theirSection);

        assertThatThrownBy(() -> enrollments.enroll(actor, requestId, UUID.randomUUID(), sectionA.sectionId(),
                TODAY)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> enrollments.enroll(actor, requestId, ana.id(), UUID.randomUUID(), TODAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> enrollments.enroll(actor, requestId, theirs.id(), sectionA.sectionId(), TODAY))
                .as("a student of another institution").isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> enrollments.enroll(actor, requestId, ana.id(), theirSection.sectionId(), TODAY))
                .as("a section of another institution").isInstanceOf(ResourceNotFoundException.class);

        assertThat(world.rowsOf(institution)).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(rolledBack()).isTrue();
    }

    @Test
    void anInactiveStudentAnInactiveSectionAndAClosedYearAreRefusedWithNothingWritten() {
        Student inactive = student("E-000002", "Luis", "Lopez", StudentStatus.INACTIVE);
        Student ana = student();

        assertThatThrownBy(() -> enroll(inactive, sectionA)).isInstanceOf(StudentNotActiveException.class);
        assertThatThrownBy(() -> enroll(ana, inactiveSection)).isInstanceOf(InactiveSectionException.class);
        assertThatThrownBy(() -> enroll(ana, closedSection)).isInstanceOf(ClosedAcademicYearException.class);

        assertThat(world.rowsOf(institution)).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(rolledBack()).isTrue();
    }

    @Test
    void theEnrollmentDateOutsideTheYearOrInTheFutureIsRefusedOnItsFieldAndNothingIsWritten() {
        Student ana = student();

        for (LocalDate bad : new LocalDate[] {YEAR_START.minusDays(1), TODAY.plusDays(1), YEAR_END.plusDays(1)}) {
            assertThatThrownBy(() -> enroll(ana, sectionA, bad)).isInstanceOfSatisfying(RequestFieldException.class,
                    e -> {
                        assertThat(e.field()).isEqualTo("enrolledOn");
                        assertThat(e.reason()).isEqualTo("enrollment-date-out-of-range");
                    });
        }
        assertThat(enroll(ana, sectionA, YEAR_START).enrolledOn()).as("the first day").isEqualTo(YEAR_START);
        assertThat(audit).hasSize(1);
    }

    @Test
    void theLastDayOfTheYearIsAcceptedOnceItIsNotInTheFuture() {
        ManageEnrollments afterTheYear = use(Instant.parse("2026-12-05T15:00:00Z"));

        EnrollmentView view = afterTheYear.enroll(actor, requestId, student().id(), sectionA.sectionId(), YEAR_END);

        assertThat(view.enrolledOn()).isEqualTo(YEAR_END);
    }

    @Test
    void aSecondActiveEnrollmentInTheSameYearIsRefusedEvenInAnotherSectionAndAllowedAfterAWithdrawal() {
        Student ana = student();
        EnrollmentView first = enroll(ana, sectionA);

        assertThatThrownBy(() -> enroll(ana, sectionB)).isInstanceOf(DuplicateActiveEnrollmentException.class);
        assertThat(world.rowsOf(institution)).hasSize(1);

        exit(first, "WITHDRAWN", TODAY, "Cambio de ciudad");
        EnrollmentView again = enroll(ana, sectionB);

        assertThat(again.status()).isEqualTo("ACTIVE");
        assertThat(world.rowsOf(institution)).hasSize(2);
    }

    @Test
    void thereIsNoSeatBeyondTheCapacityAndALeavingStudentFreesOne() {
        EnrollmentView one = enroll(student(), sectionA);
        enroll(student(), sectionA);
        Student third = student();

        assertThatThrownBy(() -> enroll(third, sectionA)).isInstanceOf(SectionFullException.class);
        assertThat(world.rowsOf(institution)).hasSize(2);

        exit(one, "WITHDRAWN", TODAY, "Cambio de ciudad");

        assertThat(enroll(third, sectionA).status()).as("the seat was freed").isEqualTo("ACTIVE");
    }

    @Test
    void aSectionWithoutCapacityTakesAsManyAsAreEnrolled() {
        for (int i = 0; i < 5; i++) {
            enroll(student(), sectionB);
        }

        assertThat(world.rowsOf(institution)).hasSize(5);
    }

    @Test
    void enrollingAuditsTheCodesAndTheDatesAndNeverTheNamesOrTheBirthDate() {
        Student ana = student("E-000001", "Ana", "Perez", StudentStatus.ACTIVE);

        EnrollmentView view = enroll(ana, sectionA);

        assertThat(audit).hasSize(1);
        AuditEntry entry = audit.getFirst();
        assertThat(entry.action()).isEqualTo("students.enrollment.created");
        assertThat(entry.entityType()).isEqualTo("students.enrollment");
        assertThat(entry.entityId()).isEqualTo(view.id().toString());
        assertThat(entry.institutionId()).isEqualTo(institutionUuid);
        assertThat(entry.actorId()).isEqualTo(actor.accountId());
        assertThat(entry.beforeValue()).isNull();
        JsonNode after = json(entry.afterValue());
        assertThat(after.propertyNames()).containsExactlyInAnyOrder("studentCode", "sectionId", "academicYearId",
                "enrolledOn", "status");
        assertThat(after.get("studentCode").asString()).isEqualTo("E-000001");
        assertThat(after.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(entry.afterValue()).doesNotContain("Ana").doesNotContain("Perez").doesNotContain("2012-05-20");
    }

    // --- Transfers between sections ---

    @Test
    void aTransferMovesTheEnrollmentToTheOtherSectionOfTheYearAndRecordsTheHistory() {
        Student ana = student();
        EnrollmentView enrolled = enroll(ana, sectionA);
        world.events.clear();
        audit.clear();

        EnrollmentView moved = enrollments.transfer(actor, requestId, enrolled.id(), sectionB.sectionId(),
                LocalDate.of(2026, 3, 5), "Cambio de turno");

        assertThat(moved.sectionId()).isEqualTo(sectionB.sectionId());
        assertThat(moved.sectionName()).isEqualTo("B");
        assertThat(moved.status()).isEqualTo("ACTIVE");
        assertThat(moved.academicYearId()).isEqualTo(yearId);
        assertThat(moved.enrolledOn()).isEqualTo(enrolled.enrolledOn());
        assertThat(moved.transfers()).hasSize(1);
        TransferView entry = moved.transfers().getFirst();
        assertThat(entry.fromSectionId()).isEqualTo(sectionA.sectionId());
        assertThat(entry.fromSectionName()).isEqualTo("A");
        assertThat(entry.toSectionId()).isEqualTo(sectionB.sectionId());
        assertThat(entry.toSectionName()).isEqualTo("B");
        assertThat(entry.transferredOn()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(entry.reason()).isEqualTo("Cambio de turno");
        assertThat(entry.transferredBy()).isEqualTo(actor.accountId());
        assertThat(world.events).as("the enrollment first, then the target section, then the writes")
                .containsSubsequence("lock-enrollment", "lock-section", "count", "update", "insert-transfer");
        assertThat(committed()).isTrue();
    }

    @Test
    void aTransferFreesTheSeatOfTheOldSectionAndTakesOneOfTheNewOne() {
        EnrollmentView first = enroll(student(), sectionA);
        enroll(student(), sectionA);
        Student third = student();
        assertThatThrownBy(() -> enroll(third, sectionA)).isInstanceOf(SectionFullException.class);

        enrollments.transfer(actor, requestId, first.id(), sectionB.sectionId(), TODAY, null);

        assertThat(world.enrollments.countActiveInSection(institution, sectionA.sectionId())).isEqualTo(1);
        assertThat(world.enrollments.countActiveInSection(institution, sectionB.sectionId())).isEqualTo(1);
        assertThat(enroll(third, sectionA).status()).isEqualTo("ACTIVE");
    }

    @Test
    void theHistoryOnlyGrowsEveryTransferIsKeptAsItWasWritten() {
        EnrollmentView enrolled = enroll(student(), sectionA);

        enrollments.transfer(actor, requestId, enrolled.id(), sectionB.sectionId(), LocalDate.of(2026, 3, 1), "Primero");
        var afterFirst = List.copyOf(world.history);
        EnrollmentView last = enrollments.transfer(actor, requestId, enrolled.id(), sectionA.sectionId(),
                LocalDate.of(2026, 3, 2), "Segundo");

        assertThat(world.history).hasSize(2).startsWith(afterFirst.getFirst());
        assertThat(last.transfers()).extracting(TransferView::reason).containsExactly("Primero", "Segundo");
        assertThat(last.transfers()).extracting(TransferView::fromSectionName).containsExactly("A", "B");
        assertThat(world.history.getFirst()).isEqualTo(afterFirst.getFirst());
    }

    @Test
    void aTransferToTheSameSectionToAnotherYearToAFullOrInactiveSectionOrOfAFinalEnrollmentIsRefused() {
        EnrollmentView enrolled = enroll(student(), sectionC);
        enroll(student(), sectionA);
        enroll(student(), sectionA);
        EnrollmentView inSectionA = enroll(student(), sectionB);

        assertThatThrownBy(() -> enrollments.transfer(actor, requestId, enrolled.id(), sectionC.sectionId(), TODAY,
                null)).isInstanceOfSatisfying(RequestFieldException.class,
                        e -> assertThat(e.field()).isEqualTo("toSectionId"));
        assertThatThrownBy(() -> enrollments.transfer(actor, requestId, enrolled.id(), nextYearSection.sectionId(),
                TODAY, null)).isInstanceOf(TransferYearMismatchException.class);
        assertThatThrownBy(() -> enrollments.transfer(actor, requestId, enrolled.id(), sectionA.sectionId(), TODAY,
                null)).isInstanceOf(SectionFullException.class);
        assertThatThrownBy(() -> enrollments.transfer(actor, requestId, enrolled.id(), inactiveSection.sectionId(),
                TODAY, null)).isInstanceOf(InactiveSectionException.class);
        assertThatThrownBy(() -> enrollments.transfer(actor, requestId, enrolled.id(), UUID.randomUUID(), TODAY,
                null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> enrollments.transfer(actor, requestId, UUID.randomUUID(), sectionB.sectionId(), TODAY,
                null)).isInstanceOf(ResourceNotFoundException.class);

        exit(inSectionA, "WITHDRAWN", TODAY, "Cambio de ciudad");
        assertThatThrownBy(() -> enrollments.transfer(actor, requestId, inSectionA.id(), sectionB.sectionId(), TODAY,
                null)).as("final").isInstanceOf(InvalidEnrollmentStateException.class);
        assertThat(world.history).isEmpty();
        assertThat(world.rowsOf(institution).stream().filter(e -> e.id().equals(enrolled.id())).findFirst().get()
                .sectionId()).isEqualTo(sectionC.sectionId());
    }

    @Test
    void aTransferInAClosedYearIsRefused() {
        Student ana = student();
        Enrollment inClosedYear = new Enrollment(UUID.randomUUID(), ana.id(), closedSection.sectionId(),
                closedSection.academicYearId(), LocalDate.of(2026, 2, 1), EnrollmentStatus.ACTIVE, null, null, NOW,
                NOW);
        world.arrange(institution, inClosedYear);
        SectionRef sameYear = new SectionRef(UUID.randomUUID(), "Y", true, 9, closedSection.academicYearId(),
                "Anio CLOSED", "CLOSED", YEAR_START, YEAR_END, grade7, "7mo", "Basica");
        world.arrange(institution, sameYear);

        assertThatThrownBy(() -> enrollments.transfer(actor, requestId, inClosedYear.id(), sameYear.sectionId(),
                TODAY, null)).isInstanceOf(ClosedAcademicYearException.class);
        assertThatThrownBy(() -> enrollments.exit(actor, requestId, inClosedYear.id(), "WITHDRAWN", TODAY,
                "Motivo")).isInstanceOf(ClosedAcademicYearException.class);
    }

    @Test
    void aTransferAuditsTheSectionsAndOnlyWhetherThereWasAReason() {
        EnrollmentView enrolled = enroll(student("E-000001", "Ana", "Perez", StudentStatus.ACTIVE), sectionA);
        audit.clear();

        enrollments.transfer(actor, requestId, enrolled.id(), sectionB.sectionId(), TODAY, "Motivo privado del acudiente");

        AuditEntry entry = audit.getFirst();
        assertThat(entry.action()).isEqualTo("students.enrollment.transferred");
        JsonNode before = json(entry.beforeValue());
        JsonNode after = json(entry.afterValue());
        assertThat(before.get("sectionId").asString()).isEqualTo(sectionA.sectionId().toString());
        assertThat(after.get("sectionId").asString()).isEqualTo(sectionB.sectionId().toString());
        assertThat(after.get("hasReason").asBoolean()).isTrue();
        assertThat(after.get("transferredOn").asString()).isEqualTo(TODAY.toString());
        assertThat(entry.beforeValue() + entry.afterValue()).doesNotContain("Motivo privado").doesNotContain("Ana")
                .doesNotContain("Perez");
    }

    // --- Exits ---

    @Test
    void anEnrollmentCanBeWithdrawnTransferredToAnotherInstitutionOrGraduated() {
        EnrollmentView a = enroll(student(), sectionB);
        EnrollmentView b = enroll(student(), sectionB);
        EnrollmentView c = enroll(student(), sectionB);

        EnrollmentView withdrawn = exit(a, "WITHDRAWN", TODAY, "Cambio de ciudad");
        EnrollmentView transferred = exit(b, "TRANSFERRED", TODAY, "Otra institucion");
        EnrollmentView graduated = exit(c, "GRADUATED", TODAY, null);

        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.withdrawnOn()).isEqualTo(TODAY);
        assertThat(withdrawn.withdrawalReason()).isEqualTo("Cambio de ciudad");
        assertThat(transferred.status()).isEqualTo("TRANSFERRED");
        assertThat(graduated.status()).isEqualTo("GRADUATED");
        assertThat(graduated.withdrawalReason()).isNull();
        assertThat(world.events).containsSubsequence("lock-enrollment", "update");
    }

    @Test
    void anExitWithoutReasonOrWithADateBeforeTheEnrollmentOrInTheFutureIsRefusedOnItsField() {
        EnrollmentView enrolled = enroll(student(), sectionB);

        assertThatThrownBy(() -> exit(enrolled, "WITHDRAWN", TODAY, null)).isInstanceOfSatisfying(
                RequestFieldException.class, e -> assertThat(e.field()).isEqualTo("withdrawalReason"));
        assertThatThrownBy(() -> exit(enrolled, "TRANSFERRED", TODAY, "  ")).isInstanceOfSatisfying(
                RequestFieldException.class, e -> assertThat(e.field()).isEqualTo("withdrawalReason"));
        assertThatThrownBy(() -> exit(enrolled, "WITHDRAWN", LocalDate.of(2026, 1, 31), "Motivo"))
                .isInstanceOfSatisfying(RequestFieldException.class, e -> {
                    assertThat(e.field()).isEqualTo("withdrawnOn");
                    assertThat(e.reason()).isEqualTo("enrollment-date-out-of-range");
                });
        assertThatThrownBy(() -> exit(enrolled, "WITHDRAWN", TODAY.plusDays(1), "Motivo"))
                .isInstanceOfSatisfying(RequestFieldException.class,
                        e -> assertThat(e.field()).isEqualTo("withdrawnOn"));
        assertThat(world.rowsOf(institution).getFirst().status()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(exit(enrolled, "GRADUATED", enrolled.enrolledOn(), null).status()).isEqualTo("GRADUATED");
    }

    @Test
    void anUnknownOrActiveExitStatusIsRefusedOnTheStatusField() {
        EnrollmentView enrolled = enroll(student(), sectionB);

        for (String bad : new String[] {"ACTIVE", "FOO", "", null}) {
            assertThatThrownBy(() -> exit(enrolled, bad, TODAY, "Motivo")).isInstanceOfSatisfying(
                    RequestFieldException.class, e -> assertThat(e.field()).isEqualTo("status"));
        }
    }

    @Test
    void afterAnExitTheEnrollmentIsFinalAndANewExitOrTransferFailsAsInvalidState() {
        EnrollmentView enrolled = enroll(student(), sectionB);
        exit(enrolled, "WITHDRAWN", TODAY, "Cambio de ciudad");

        for (String again : new String[] {"WITHDRAWN", "TRANSFERRED", "GRADUATED"}) {
            assertThatThrownBy(() -> exit(enrolled, again, TODAY, "Motivo"))
                    .isInstanceOf(InvalidEnrollmentStateException.class);
        }
        assertThat(world.rowsOf(institution).getFirst().withdrawalReason()).isEqualTo("Cambio de ciudad");
    }

    @Test
    void anUnknownEnrollmentCannotBeExited() {
        assertThatThrownBy(() -> enrollments.exit(actor, requestId, UUID.randomUUID(), "WITHDRAWN", TODAY, "Motivo"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anExitAuditsTheStatesAndTheDateAndOnlyWhetherThereWasAReason() {
        EnrollmentView enrolled = enroll(student("E-000001", "Ana", "Perez", StudentStatus.ACTIVE), sectionB);
        audit.clear();

        exit(enrolled, "WITHDRAWN", TODAY, "Se muda a San Pedro Sula por trabajo");

        AuditEntry entry = audit.getFirst();
        assertThat(entry.action()).isEqualTo("students.enrollment.exited");
        assertThat(json(entry.beforeValue()).get("status").asString()).isEqualTo("ACTIVE");
        JsonNode after = json(entry.afterValue());
        assertThat(after.get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(after.get("withdrawnOn").asString()).isEqualTo(TODAY.toString());
        assertThat(after.get("hasWithdrawalReason").asBoolean()).isTrue();
        assertThat(entry.beforeValue() + entry.afterValue()).doesNotContain("San Pedro").doesNotContain("Ana")
                .doesNotContain("Perez");
    }

    // --- Reading ---

    @Test
    void theDetailHasTheHistoryAndAnUnknownOrForeignEnrollmentIsNotFound() {
        EnrollmentView enrolled = enroll(student(), sectionA);
        enrollments.transfer(actor, requestId, enrolled.id(), sectionB.sectionId(), TODAY, null);
        readOnlyFlags.clear();

        EnrollmentView detail = enrollments.get(actor, requestId, enrolled.id());

        assertThat(detail.sectionName()).isEqualTo("B");
        assertThat(detail.transfers()).hasSize(1);
        assertThat(readOnlyFlags).contains("true");
        assertThatThrownBy(() -> enrollments.get(actor, requestId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> enrollments.get(actorOf(UUID.randomUUID()), requestId, enrolled.id()))
                .as("another institution").isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theListDefaultsToTheActiveYearOrderedByStudentLastNameAndCarriesNoHistory() {
        enroll(student("E-000001", "Ana", "Zelaya", StudentStatus.ACTIVE), sectionA);
        enroll(student("E-000002", "Luis", "Alvarado", StudentStatus.ACTIVE), sectionB);
        Student inNextYear = student();
        enrollments.enroll(actor, requestId, inNextYear.id(), nextYearSection.sectionId(), LocalDate.of(2026, 3, 1));

        Page<EnrollmentView> page = enrollments.list(actor, requestId, 0, 50, null, null, null, null, null);

        assertThat(world.lastFilter.academicYearId()).isEqualTo(yearId);
        assertThat(world.lastRequest.sortField()).isEqualTo("studentLastName");
        assertThat(world.lastRequest.ascending()).isTrue();
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(EnrollmentView::studentLastName).containsExactly("Alvarado", "Zelaya");
        assertThat(page.items()).allSatisfy(view -> assertThat(view.transfers()).isNull());
    }

    @Test
    void withoutAnActiveYearTheListCoversEveryYear() {
        world.activeYears.clear();
        enroll(student(), sectionA);
        enrollments.enroll(actor, requestId, student().id(), nextYearSection.sectionId(), LocalDate.of(2026, 3, 1));

        Page<EnrollmentView> page = enrollments.list(actor, requestId, 0, 50, null, null, null, null, null);

        assertThat(world.lastFilter.academicYearId()).isNull();
        assertThat(page.total()).isEqualTo(2);
        assertThat(enrollments.list(actor, requestId, 0, 50, null, otherYearId, null, null, null).total())
                .as("an explicit year wins").isEqualTo(1);
    }

    @Test
    void theGradeFilterIsResolvedThroughTheSectionsOfTheGradeAndCombinesWithTheSectionFilter() {
        enroll(student(), sectionA);
        enroll(student(), sectionB);
        enroll(student(), sectionC);

        assertThat(enrollments.list(actor, requestId, 0, 50, null, null, null, grade7, null).total()).isEqualTo(2);
        assertThat(enrollments.list(actor, requestId, 0, 50, null, null, null, grade8, null).total()).isEqualTo(1);
        assertThat(enrollments.list(actor, requestId, 0, 50, null, null, sectionA.sectionId(), grade7, null).total())
                .isEqualTo(1);
        assertThat(enrollments.list(actor, requestId, 0, 50, null, null, sectionA.sectionId(), grade8, null).total())
                .as("a section outside the grade keeps nothing").isZero();
        assertThat(enrollments.list(actor, requestId, 0, 50, null, null, sectionC.sectionId(), null, null).total())
                .isEqualTo(1);
        assertThat(enrollments.list(actor, requestId, 0, 50, null, null, null, UUID.randomUUID(), null).total())
                .as("a grade with no sections").isZero();
    }

    @Test
    void theStatusFilterAndTheSortAreAppliedAndBadParametersAreRefused() {
        EnrollmentView a = enroll(student(), sectionB);
        enroll(student(), sectionB);
        exit(a, "GRADUATED", TODAY, null);

        assertThat(enrollments.list(actor, requestId, 0, 50, "enrolledOn,desc", null, null, null, "GRADUATED").total())
                .isEqualTo(1);
        assertThat(world.lastRequest.sortField()).isEqualTo("enrolledOn");
        assertThat(world.lastRequest.ascending()).isFalse();
        assertThatThrownBy(() -> enrollments.list(actor, requestId, 0, 50, null, null, null, null, "FOO"))
                .isInstanceOfSatisfying(RequestFieldException.class, e -> assertThat(e.field()).isEqualTo("status"));
        for (String badSort : new String[] {"birthDate", "studentLastName,sideways", "a,b,c"}) {
            assertThatThrownBy(() -> enrollments.list(actor, requestId, 0, 50, badSort, null, null, null, null))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("validation-failed");
        }
        assertThatThrownBy(() -> enrollments.list(actor, requestId, 0, 201, null, null, null, null, null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> enrollments.list(actor, requestId, -1, 50, null, null, null, null, null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void theEnrollmentsOfAStudentComeMostRecentFirstAndAnUnknownStudentIsNotFound() {
        Student ana = student();
        EnrollmentView first = enroll(ana, sectionA, LocalDate.of(2026, 2, 1));
        exit(first, "WITHDRAWN", LocalDate.of(2026, 2, 10), "Cambio de ciudad");
        enroll(ana, sectionB, LocalDate.of(2026, 3, 1));

        List<EnrollmentView> mine = enrollments.ofStudent(actor, requestId, ana.id());

        assertThat(mine).extracting(EnrollmentView::enrolledOn).containsExactly(LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 2, 1));
        assertThat(mine).extracting(EnrollmentView::sectionName).containsExactly("B", "A");
        assertThatThrownBy(() -> enrollments.ofStudent(actor, requestId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- Values that carry personal data ---

    @Test
    void theViewsAndRowsLogNoNameNoReasonAndNoCode() {
        EnrollmentView view = enroll(student("E-000001", "Wenceslao", "Marcador", StudentStatus.ACTIVE), sectionA);
        EnrollmentView exited = exit(view, "WITHDRAWN", TODAY, "Motivo privado");

        assertThat(exited.toString()).doesNotContain("Wenceslao").doesNotContain("Marcador")
                .doesNotContain("Motivo privado").contains(exited.id().toString());
        assertThat(world.enrollments.list(institution, PageRequest.of(0, 10, null, ManageEnrollments.SORT_FIELDS,
                "studentLastName", true), new EnrollmentFilter(null, null, null)).items().getFirst().toString())
                .doesNotContain("Wenceslao").doesNotContain("Marcador");
    }

    @Test
    void anEnrollmentCannotBeBuiltForAnActiveStatusWithoutTheNullExitData() {
        assertThatThrownBy(() -> new Enrollment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), TODAY, EnrollmentStatus.ACTIVE, TODAY, null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
