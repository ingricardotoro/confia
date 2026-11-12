package com.confia.guardians.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianLink;
import com.confia.guardians.domain.GuardianNotActiveException;
import com.confia.guardians.domain.GuardianStatus;
import com.confia.guardians.domain.InvalidGuardianException;
import com.confia.guardians.domain.PreferredChannel;
import com.confia.guardians.domain.ResourceNotFoundException;
import com.confia.guardians.domain.StudentNotActiveException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentRef;
import com.confia.support.JdbcStubTransactions;
import java.math.BigDecimal;
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
 * The rules and edge cases of docs/cortes/guardians-registry.md for the links of a student, through the use case,
 * with the ports in memory and a transaction stub that tells a commit from a rollback. The races (two replacements
 * of the same student) are settled by the lock on the student's row and proven against PostgreSQL in {@code
 * GuardianEndpointsIT}; here the order in which the use case takes its locks and the exactness of INV-32 are proven.
 */
class ManageGuardianLinksTest {

    private static final Instant NOW = Instant.parse("2026-03-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<String> readOnlyFlags = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final InMemoryGuardians world = new InMemoryGuardians();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls, readOnlyFlags);
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = actorOf(institutionUuid);
    private final String requestId = UUID.randomUUID().toString();
    private final ManageGuardianLinks links = new ManageGuardianLinks(runner, world.guardians, world.links,
            world.students, auditWriter, Clock.fixed(NOW, ZoneOffset.UTC));

    private final StudentRef student = new StudentRef(UUID.randomUUID(), "E-000001", "Nombre-Alumno", "Apellido-Alumno",
            true);

    {
        world.arrange(institution, student);
    }

    private static AuthenticatedActor actorOf(UUID institutionId) {
        return new AuthenticatedActor(UUID.randomUUID(), institutionId, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private Guardian guardian(String code, String first, GuardianStatus status) {
        Guardian guardian = new Guardian(UUID.randomUUID(), code, first, "Apellido", false, false,
                first.toLowerCase() + "@example.org", "99998888", "Direccion-Privada", PreferredChannel.NONE, status,
                NOW, NOW);
        world.arrange(institution, guardian);
        return guardian;
    }

    private Guardian guardian(String code, String first) {
        return guardian(code, first, GuardianStatus.ACTIVE);
    }

    private static LinkRequest request(Guardian guardian, String relationship, String percent, boolean primary,
            boolean billing) {
        return new LinkRequest(guardian.id(), relationship, new BigDecimal(percent), primary, billing);
    }

    private StudentGuardiansView replace(LinkRequest... requested) {
        return links.replace(actor, requestId, student.studentId(), List.of(requested));
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

    // --- The acceptance flow ---

    @Test
    void sixtyFortyThenFiftyFiftyKeepsTheFirstSplitAsClosedHistory() {
        Guardian mother = guardian("P-000001", "Madre");
        Guardian father = guardian("P-000002", "Padre");

        StudentGuardiansView first = replace(request(mother, "MOTHER", "60", true, true),
                request(father, "FATHER", "40", false, false));
        assertThat(first.totalPercent()).isEqualByComparingTo("100.00");
        assertThat(first.links()).extracting(StudentGuardianView::financialResponsibilityPercent)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("60"), new BigDecimal("40"));
        List<GuardianLink> firstLinks = world.allLinks(institution, student.studentId());

        StudentGuardiansView second = replace(request(mother, "MOTHER", "50", true, true),
                request(father, "FATHER", "50", false, false));

        assertThat(second.totalPercent()).isEqualByComparingTo("100.00");
        List<GuardianLink> history = world.allLinks(institution, student.studentId());
        assertThat(history).hasSize(4);
        assertThat(history.stream().filter(GuardianLink::current)).hasSize(2)
                .allSatisfy(link -> assertThat(link.percent()).isEqualByComparingTo("50"));
        assertThat(history.stream().filter(link -> !link.current())).hasSize(2).allSatisfy(link -> {
            assertThat(link.validTo()).isEqualTo(TODAY);
            assertThat(firstLinks).extracting(GuardianLink::id).contains(link.id());
        });
        // The closed rows are exactly the ones that were first written: nothing was edited, nothing was deleted.
        assertThat(history.stream().filter(link -> !link.current()).map(GuardianLink::percent))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("60"), new BigDecimal("40"));
        assertThat(committed()).isTrue();
    }

    @Test
    void sixtyThirtyIsRejectedAndLeavesTheCurrentSplitAndNoAuditEntry() {
        Guardian mother = guardian("P-000001", "Madre");
        Guardian father = guardian("P-000002", "Padre");
        replace(request(mother, "MOTHER", "50", false, false), request(father, "FATHER", "50", false, false));
        audit.clear();
        world.events.clear();

        assertThatThrownBy(() -> replace(request(mother, "MOTHER", "60", false, false),
                request(father, "FATHER", "30", false, false)))
                .isInstanceOfSatisfying(RejectedSplitException.class, e -> {
                    assertThat(e.field()).isEqualTo("links");
                    assertThat(e.reason()).isEqualTo("sum-not-100");
                    assertThat(e.code()).isEqualTo("invalid-responsibility-split");
                });

        assertThat(world.allLinks(institution, student.studentId())).hasSize(2)
                .allSatisfy(link -> assertThat(link.percent()).isEqualByComparingTo("50"));
        assertThat(audit).isEmpty();
        // A rule of the request is judged before any lock or read is taken.
        assertThat(world.events).isEmpty();
    }

    // --- INV-32 at the boundaries ---

    @Test
    void ninetyNinePointNinetyNineAndOneHundredPointZeroOneAreRejectedAndThreeThirdsAreAccepted() {
        Guardian a = guardian("P-000001", "A");
        Guardian b = guardian("P-000002", "B");
        Guardian c = guardian("P-000003", "C");

        assertThatThrownBy(() -> replace(request(a, "MOTHER", "50", false, false),
                request(b, "FATHER", "49.99", false, false)))
                .isInstanceOfSatisfying(RejectedSplitException.class, e -> assertThat(e.reason()).isEqualTo("sum-not-100"));
        assertThatThrownBy(() -> replace(request(a, "MOTHER", "50", false, false),
                request(b, "FATHER", "50.01", false, false)))
                .isInstanceOfSatisfying(RejectedSplitException.class, e -> assertThat(e.reason()).isEqualTo("sum-not-100"));

        StudentGuardiansView view = replace(request(a, "MOTHER", "33.33", false, false),
                request(b, "FATHER", "33.33", false, false), request(c, "OTHER", "33.34", false, false));
        assertThat(view.totalPercent()).isEqualByComparingTo("100.00");
        assertThat(view.totalPercent().toPlainString()).isEqualTo("100.00");
    }

    @Test
    void percentagesOfZeroAndOneHundredPointZeroOneAreRejectedAsInvalidValues() {
        Guardian a = guardian("P-000001", "A");

        assertThatThrownBy(() -> replace(request(a, "MOTHER", "0", false, false)))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> replace(request(a, "MOTHER", "100.01", false, false)))
                .isInstanceOf(InvalidGuardianException.class);
        assertThat(replace(request(a, "MOTHER", "100", false, false)).totalPercent()).isEqualByComparingTo("100.00");
    }

    @Test
    void twoPrimaryPayersTwoBillingRecipientsAndARepeatedGuardianAreRejectedWithTheirReason() {
        Guardian a = guardian("P-000001", "A");
        Guardian b = guardian("P-000002", "B");

        assertThatThrownBy(() -> replace(request(a, "MOTHER", "50", true, false), request(b, "FATHER", "50", true, false)))
                .isInstanceOfSatisfying(RejectedSplitException.class,
                        e -> assertThat(e.reason()).isEqualTo("multiple-primary-payer"));
        assertThatThrownBy(() -> replace(request(a, "MOTHER", "50", false, true), request(b, "FATHER", "50", false, true)))
                .isInstanceOfSatisfying(RejectedSplitException.class,
                        e -> assertThat(e.reason()).isEqualTo("multiple-billing-recipient"));
        assertThatThrownBy(() -> replace(request(a, "MOTHER", "50", false, false), request(a, "FATHER", "50", false, false)))
                .isInstanceOfSatisfying(RejectedSplitException.class,
                        e -> assertThat(e.reason()).isEqualTo("duplicate-guardian"));
        assertThat(world.allLinks(institution, student.studentId())).isEmpty();
    }

    @Test
    void anEmptySetIsValidForAStudentWithoutGuardiansAndRemovesTheCurrentOnesKeepingTheHistory() {
        Guardian a = guardian("P-000001", "A");

        StudentGuardiansView nothing = replace();
        assertThat(nothing.links()).isEmpty();
        assertThat(nothing.totalPercent()).isEqualByComparingTo("0.00");
        assertThat(audit).as("nothing changed, so nothing is audited").isEmpty();

        replace(request(a, "MOTHER", "100", true, true));
        StudentGuardiansView removed = replace();

        assertThat(removed.links()).isEmpty();
        assertThat(removed.totalPercent()).isEqualByComparingTo("0.00");
        assertThat(world.allLinks(institution, student.studentId())).hasSize(1)
                .allSatisfy(link -> assertThat(link.validTo()).isEqualTo(TODAY));
    }

    @Test
    void anInactiveGuardianCannotBeLinkedAndNothingIsWritten() {
        Guardian active = guardian("P-000001", "A");
        Guardian inactive = guardian("P-000002", "B", GuardianStatus.INACTIVE);

        assertThatThrownBy(() -> replace(request(active, "MOTHER", "50", false, false),
                request(inactive, "FATHER", "50", false, false)))
                .isInstanceOf(GuardianNotActiveException.class).extracting("code").isEqualTo("guardian-not-active");

        assertThat(world.allLinks(institution, student.studentId())).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(rolledBack()).isTrue();
    }

    @Test
    void anInactiveStudentAdmitsNoChangeNotEvenAnEmptySet() {
        StudentRef inactive = new StudentRef(UUID.randomUUID(), "E-000009", "Nombre", "Apellido", false);
        world.arrange(institution, inactive);
        Guardian a = guardian("P-000001", "A");

        assertThatThrownBy(() -> links.replace(actor, requestId, inactive.studentId(),
                List.of(request(a, "MOTHER", "100", false, false))))
                .isInstanceOf(StudentNotActiveException.class).extracting("code").isEqualTo("student-not-active");
        assertThatThrownBy(() -> links.replace(actor, requestId, inactive.studentId(), List.of()))
                .isInstanceOf(StudentNotActiveException.class);
        assertThat(audit).isEmpty();
    }

    @Test
    void anUnknownStudentOrGuardianOrOneOfAnotherInstitutionIsNotFound() {
        Guardian a = guardian("P-000001", "A");
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        Guardian foreign = new Guardian(UUID.randomUUID(), "P-000001", "Ajena", "Otra", false, false, null, null, null,
                PreferredChannel.NONE, GuardianStatus.ACTIVE, NOW, NOW);
        world.arrange(other, foreign);

        assertThatThrownBy(() -> links.replace(actor, requestId, UUID.randomUUID(),
                List.of(request(a, "MOTHER", "100", false, false)))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> replace(request(new Guardian(UUID.randomUUID(), "P-000009", "Nadie", "Nadie", false,
                false, null, null, null, PreferredChannel.NONE, GuardianStatus.ACTIVE, NOW, NOW), "MOTHER", "100", false,
                false))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> replace(request(foreign, "MOTHER", "100", false, false)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(world.allLinks(institution, student.studentId())).isEmpty();
    }

    // --- The history ---

    @Test
    void anIdenticalReplacementWritesNothingAndLeavesNoAuditEntry() {
        Guardian mother = guardian("P-000001", "Madre");
        Guardian father = guardian("P-000002", "Padre");
        replace(request(mother, "MOTHER", "60", true, false), request(father, "FATHER", "40", false, true));
        audit.clear();
        world.events.clear();

        StudentGuardiansView again = replace(request(mother, "MOTHER", "60.00", true, false),
                request(father, "FATHER", "40.0", false, true));

        assertThat(again.links()).hasSize(2);
        assertThat(world.allLinks(institution, student.studentId())).hasSize(2).allSatisfy(link -> assertThat(link.current()).isTrue());
        assertThat(audit).isEmpty();
        assertThat(world.events).doesNotContain("close-link", "insert-link");
        assertThat(committed()).isTrue();
    }

    @Test
    void aReplacementThatChangesOnlyOneGuardianClosesAndOpensOnlyThatOne() {
        Guardian mother = guardian("P-000001", "Madre");
        Guardian father = guardian("P-000002", "Padre");
        Guardian aunt = guardian("P-000003", "Tia");
        replace(request(mother, "MOTHER", "60", true, false), request(father, "FATHER", "40", false, true));
        world.events.clear();

        replace(request(mother, "MOTHER", "60", true, false), request(aunt, "OTHER", "40", false, true));

        List<GuardianLink> history = world.allLinks(institution, student.studentId());
        assertThat(history).hasSize(3);
        assertThat(history.stream().filter(GuardianLink::current).map(GuardianLink::guardianId))
                .containsExactlyInAnyOrder(mother.id(), aunt.id());
        assertThat(history.stream().filter(link -> !link.current()).map(GuardianLink::guardianId))
                .containsExactly(father.id());
        assertThat(world.events.stream().filter("close-link"::equals)).hasSize(1);
        assertThat(world.events.stream().filter("insert-link"::equals)).hasSize(1);
    }

    @Test
    void aGuardianRemovedAndLaterAddedBackGetsANewLinkAndTheClosedOneStaysClosed() {
        Guardian mother = guardian("P-000001", "Madre");
        Guardian father = guardian("P-000002", "Padre");
        replace(request(mother, "MOTHER", "60", false, false), request(father, "FATHER", "40", false, false));
        replace(request(mother, "MOTHER", "100", false, false));
        replace(request(mother, "MOTHER", "60", false, false), request(father, "FATHER", "40", false, false));

        List<GuardianLink> history = world.allLinks(institution, student.studentId());
        assertThat(history).hasSize(5);
        assertThat(history.stream().filter(GuardianLink::current)).hasSize(2);
        assertThat(history.stream().map(GuardianLink::id).distinct()).hasSize(5);
    }

    @Test
    void theLinksAreClosedBeforeTheNewOnesAreInsertedSoTheUniqueIndexesNeverSeeTwoCurrentOnes() {
        Guardian mother = guardian("P-000001", "Madre");
        replace(request(mother, "MOTHER", "100", true, true));
        world.events.clear();

        replace(request(mother, "MOTHER", "100", false, true));

        assertThat(world.events).containsSubsequence("close-link", "insert-link");
    }

    // --- Locks, transaction, audit ---

    @Test
    void theStudentIsLockedBeforeTheCurrentLinksAreReadAndThenTheGuardiansInShareMode() {
        Guardian mother = guardian("P-000001", "Madre");
        world.events.clear();

        replace(request(mother, "MOTHER", "100", false, false));

        assertThat(world.events).containsSubsequence("lock-student", "read-links", "lock-guardians-shared",
                "insert-link");
        assertThat(readOnlyFlags).as("the replacement is a read-write transaction").doesNotContain("true");
        assertThat(committed()).isTrue();
    }

    @Test
    void aFailureLeavesTheTransactionRolledBack() {
        assertThatThrownBy(() -> links.replace(actor, requestId, UUID.randomUUID(), List.of()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(rolledBack()).isTrue();
    }

    @Test
    void theAuditEntryNamesCodesRelationshipsAndPercentagesButNoNameNoStudentNameNoContactData() {
        Guardian mother = guardian("P-000001", "Nombre-Privado-Madre");
        Guardian father = guardian("P-000002", "Nombre-Privado-Padre");
        replace(request(mother, "MOTHER", "60", true, true), request(father, "FATHER", "40", false, false));
        audit.clear();

        replace(request(mother, "MOTHER", "50", true, true), request(father, "FATHER", "50", false, false));

        assertThat(audit).hasSize(1);
        AuditEntry entry = audit.getFirst();
        assertThat(entry.action()).isEqualTo("guardians.links.replaced");
        assertThat(entry.entityType()).isEqualTo("guardians.links");
        assertThat(entry.entityId()).isEqualTo(student.studentId().toString());
        assertThat(entry.institutionId()).isEqualTo(institutionUuid);
        assertThat(entry.actorId()).isEqualTo(actor.accountId());
        assertThat(entry.outcome()).isEqualTo("success");
        JsonNode before = json(entry.beforeValue());
        JsonNode after = json(entry.afterValue());
        assertThat(before.get("studentCode").asString()).isEqualTo("E-000001");
        assertThat(before.get("linkCount").asInt()).isEqualTo(2);
        assertThat(before.get("totalPercent").asString()).isEqualTo("100.00");
        assertThat(before.get("links").asString()).contains("P-000001:MOTHER:60.00:primary:billing")
                .contains("P-000002:FATHER:40.00");
        assertThat(after.get("links").asString()).contains("P-000001:MOTHER:50.00:primary:billing")
                .contains("P-000002:FATHER:50.00");
        String both = entry.beforeValue() + entry.afterValue();
        assertThat(both).doesNotContain("Nombre-Privado").doesNotContain("Alumno").doesNotContain("example.org")
                .doesNotContain("99998888").doesNotContain("Direccion-Privada");
    }

    @Test
    void theCreationOfTheFirstSetAuditsAnEmptyBeforeAndTheNewLinks() {
        Guardian mother = guardian("P-000001", "Madre");

        replace(request(mother, "MOTHER", "100", false, false));

        assertThat(audit).hasSize(1);
        assertThat(json(audit.getFirst().beforeValue()).get("linkCount").asInt()).isZero();
        assertThat(json(audit.getFirst().beforeValue()).get("totalPercent").asString()).isEqualTo("0.00");
        assertThat(json(audit.getFirst().afterValue()).get("links").asString()).isEqualTo("P-000001:MOTHER:100.00");
    }

    // --- Reading ---

    @Test
    void readingListsTheCurrentLinksWithTheExactTotalInAReadOnlyTransaction() {
        Guardian mother = guardian("P-000001", "Madre");
        Guardian father = guardian("P-000002", "Padre");
        replace(request(mother, "MOTHER", "33.33", true, false), request(father, "FATHER", "66.67", false, true));
        readOnlyFlags.clear();

        world.events.clear();
        StudentGuardiansView view = links.of(actor, requestId, student.studentId());

        assertThat(view.studentCode()).isEqualTo("E-000001");
        assertThat(view.totalPercent()).isEqualByComparingTo("100.00");
        assertThat(view.links()).extracting(StudentGuardianView::guardianCode)
                .containsExactlyInAnyOrder("P-000001", "P-000002");
        assertThat(view.links()).filteredOn(StudentGuardianView::primaryPayer).singleElement()
                .satisfies(link -> assertThat(link.guardianFirstName()).isEqualTo("Madre"));
        assertThat(readOnlyFlags).contains("true");
        assertThat(world.events).doesNotContain("lock-student");
    }

    @Test
    void readingAnUnknownStudentOrOneOfAnotherInstitutionIsNotFound() {
        assertThatThrownBy(() -> links.of(actor, requestId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        AuthenticatedActor stranger = actorOf(UUID.randomUUID());
        assertThatThrownBy(() -> links.of(stranger, requestId, student.studentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> links.replace(stranger, requestId, student.studentId(), List.of()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theViewsNeverPrintPersonalData() {
        Guardian mother = guardian("P-000001", "Nombre-Privado-Madre");
        StudentGuardiansView view = replace(request(mother, "MOTHER", "100", false, false));

        assertThat(view.links().getFirst().toString()).doesNotContain("Nombre-Privado");
        assertThat(world.guardiansOf(institution).getFirst().guardian().toString()).doesNotContain("Nombre-Privado");
    }
}
