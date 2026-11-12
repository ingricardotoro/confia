package com.confia.guardians.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.guardians.domain.DuplicateNationalIdException;
import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianLink;
import com.confia.guardians.domain.GuardianStatus;
import com.confia.guardians.domain.InvalidGuardianException;
import com.confia.guardians.domain.PreferredChannel;
import com.confia.guardians.domain.Relationship;
import com.confia.guardians.domain.ResourceNotFoundException;
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
 * The registry of docs/cortes/guardians-registry.md through the use case, with the ports in memory: the code from
 * the counter, the protected document and RTN that never come back, the duplicate document, the edit that keeps,
 * removes or replaces them, a guardian made inactive that keeps its links, the list, and an audit trail without
 * personal data.
 */
class ManageGuardiansTest {

    private static final Instant NOW = Instant.parse("2026-03-10T15:00:00Z");
    private static final String ID = "0801-1999-12345";
    private static final String ID_NORMALIZED = "0801199912345";
    private static final String RTN = "0801-1999-123456";
    private static final String RTN_DIGITS = "08011999123456";

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
    private final ManageGuardians guardians = new ManageGuardians(runner, world.guardians, world.links, world.students,
            world.protector, auditWriter, Clock.fixed(NOW, ZoneOffset.UTC));

    private static AuthenticatedActor actorOf(UUID institutionId) {
        return new AuthenticatedActor(UUID.randomUUID(), institutionId, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private GuardianView create(String first, String last, String nationalId, String rtn) {
        return guardians.create(actor, requestId, first, last, nationalId, rtn, null, null, null, null);
    }

    private GuardianView update(GuardianView view, String first, String nationalId, String rtn, String status) {
        return guardians.update(actor, requestId, view.id(), first, view.lastName(), nationalId, rtn, view.email(),
                view.phone(), view.address(), view.preferredChannel(), status);
    }

    private static JsonNode json(String document) {
        return JsonMapper.builder().build().readTree(document);
    }

    // --- Creating ---

    @Test
    void creatingGeneratesTheCodeFromTheCounterOfTheInstitution() {
        assertThat(create("Ana", "Lopez", null, null).guardianCode()).isEqualTo("P-000001");
        assertThat(create("Eva", "Mejia", null, null).guardianCode()).isEqualTo("P-000002");

        AuthenticatedActor other = actorOf(UUID.randomUUID());
        assertThat(guardians.create(other, requestId, "Rosa", "Diaz", null, null, null, null, null, null)
                .guardianCode()).isEqualTo("P-000001");
    }

    @Test
    void aGeneratedCodeThatAnExistingGuardianAlreadyUsesIsSkipped() {
        world.arrange(institution, new Guardian(UUID.randomUUID(), "P-000001", "Previo", "Previo", false, false, null,
                null, null, PreferredChannel.NONE, GuardianStatus.ACTIVE, NOW, NOW));

        assertThat(create("Ana", "Lopez", null, null).guardianCode()).isEqualTo("P-000002");
    }

    @Test
    void theNewGuardianIsActiveWithNoChannelAndTheViewCarriesNoStudents() {
        GuardianView view = guardians.create(actor, requestId, " Ana ", " Lopez ", null, null, "ana@example.org",
                "9999-8888", "Casa 1", "WHATSAPP");

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.firstName()).isEqualTo("Ana");
        assertThat(view.preferredChannel()).isEqualTo("WHATSAPP");
        assertThat(view.email()).isEqualTo("ana@example.org");
        assertThat(view.students()).isEmpty();
        assertThat(create("Eva", "Mejia", null, null).preferredChannel()).isEqualTo("NONE");
        assertThat(view.createdAt()).isEqualTo(NOW).isEqualTo(view.updatedAt());
    }

    @Test
    void theDocumentAndTheRtnAreProtectedBeforeTheyAreStoredAndTheViewOnlySaysTheyExist() {
        GuardianView view = create("Ana", "Lopez", ID, RTN);

        assertThat(view.hasNationalId()).isTrue();
        assertThat(view.hasRtn()).isTrue();
        InMemoryGuardians.Stored stored = world.guardiansOf(institution).getFirst();
        assertThat(stored.document().encrypted()).isEqualTo("enc[" + ID_NORMALIZED + "]");
        assertThat(stored.rtn()).isEqualTo("enc[" + RTN_DIGITS + "]");
        assertThat(view.toString()).doesNotContain(ID_NORMALIZED).doesNotContain(RTN_DIGITS).doesNotContain("Ana");
        assertThat(world.events).containsSubsequence("protect-document", "insert-guardian");
        assertThat(stored.guardian().toString()).doesNotContain("Ana");
    }

    @Test
    void aBlankDocumentOrRtnIsNoDocumentOrRtn() {
        GuardianView view = create("Ana", "Lopez", "  ", "");

        assertThat(view.hasNationalId()).isFalse();
        assertThat(view.hasRtn()).isFalse();
        assertThat(world.events).doesNotContain("protect-document", "protect-rtn");
    }

    @Test
    void theSameDocumentInTheSameInstitutionIsADuplicateWhateverItsFormatAndAnotherInstitutionMayRepeatIt() {
        create("Ana", "Lopez", ID, null);

        assertThatThrownBy(() -> create("Eva", "Mejia", ID_NORMALIZED, null))
                .isInstanceOf(DuplicateNationalIdException.class).extracting("code").isEqualTo("duplicate-national-id");
        assertThatThrownBy(() -> create("Eva", "Mejia", "0801-1999-12345".toLowerCase(), null))
                .isInstanceOf(DuplicateNationalIdException.class);
        assertThat(world.guardiansOf(institution)).hasSize(1);

        AuthenticatedActor other = actorOf(UUID.randomUUID());
        assertThat(guardians.create(other, requestId, "Rosa", "Diaz", ID, null, null, null, null, null).hasNationalId())
                .isTrue();
    }

    @Test
    void theSameRtnMayBelongToTwoGuardians() {
        create("Ana", "Lopez", null, RTN);

        assertThat(create("Eva", "Mejia", null, RTN).hasRtn()).isTrue();
    }

    @Test
    void invalidValuesAreRefusedAndNothingIsWritten() {
        assertThatThrownBy(() -> create("", "Lopez", null, null)).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> create("Ana", "Lopez", "123", null)).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> create("Ana", "Lopez", null, "123")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> guardians.create(actor, requestId, "Ana", "Lopez", null, null, "no-email", null, null,
                null)).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> guardians.create(actor, requestId, "Ana", "Lopez", null, null, null, "12", null, null))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> guardians.create(actor, requestId, "Ana", "Lopez", null, null, null, null, null, "FAX"))
                .isInstanceOf(InvalidGuardianException.class);

        assertThat(world.guardiansOf(institution)).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void creatingAuditsTheCodeTheStatusAndWhichMembersExistButNoPersonalData() {
        guardians.create(actor, requestId, "Nombre-Privado", "Apellido-Privado", ID, RTN, "privado@example.org",
                "99998888", "Direccion-Privada", "SMS");

        assertThat(audit).hasSize(1);
        AuditEntry entry = audit.getFirst();
        assertThat(entry.action()).isEqualTo("guardians.guardian.created");
        assertThat(entry.entityType()).isEqualTo("guardians.guardian");
        assertThat(entry.beforeValue()).isNull();
        JsonNode after = json(entry.afterValue());
        assertThat(after.get("guardianCode").asString()).isEqualTo("P-000001");
        assertThat(after.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(after.get("hasNationalId").asBoolean()).isTrue();
        assertThat(after.get("hasRtn").asBoolean()).isTrue();
        assertThat(after.get("hasEmail").asBoolean()).isTrue();
        assertThat(after.get("hasPhone").asBoolean()).isTrue();
        assertThat(after.get("hasAddress").asBoolean()).isTrue();
        assertThat(entry.afterValue()).doesNotContain("Privado").doesNotContain("0801").doesNotContain("99998888")
                .doesNotContain("example.org").doesNotContain("Direccion");
        assertThat(entry.institutionId()).isEqualTo(institutionUuid);
        assertThat(entry.actorId()).isEqualTo(actor.accountId());
    }

    // --- Editing ---

    @Test
    void anOmittedDocumentAndRtnAreKeptABlankOneRemovesThemAndAValueReplacesThem() {
        GuardianView created = create("Ana", "Lopez", ID, RTN);

        GuardianView kept = update(created, "Ana", null, null, "ACTIVE");
        assertThat(kept.hasNationalId()).isTrue();
        assertThat(kept.hasRtn()).isTrue();
        assertThat(world.guardiansOf(institution).getFirst().document().encrypted()).contains(ID_NORMALIZED);

        GuardianView onlyRtnRemoved = update(created, "Ana", null, "", "ACTIVE");
        assertThat(onlyRtnRemoved.hasNationalId()).isTrue();
        assertThat(onlyRtnRemoved.hasRtn()).isFalse();
        assertThat(world.guardiansOf(institution).getFirst().rtn()).isNull();

        GuardianView removed = update(created, "Ana", " ", null, "ACTIVE");
        assertThat(removed.hasNationalId()).isFalse();
        assertThat(world.guardiansOf(institution).getFirst().document()).isNull();

        GuardianView replaced = update(created, "Ana", "9999-8888-77777", "0801-1999-654321", "ACTIVE");
        assertThat(replaced.hasNationalId()).isTrue();
        assertThat(replaced.hasRtn()).isTrue();
        assertThat(world.guardiansOf(institution).getFirst().document().encrypted()).contains("9999888877777");
    }

    @Test
    void replacingTheDocumentWithOneAnotherGuardianHasIsADuplicateButKeepingItsOwnIsNot() {
        GuardianView ana = create("Ana", "Lopez", ID, null);
        GuardianView eva = create("Eva", "Mejia", "7777-6666-55555", null);

        assertThatThrownBy(() -> update(eva, "Eva", ID, null, "ACTIVE")).isInstanceOf(DuplicateNationalIdException.class);
        assertThat(update(ana, "Ana", ID, null, "ACTIVE").hasNationalId()).isTrue();
    }

    @Test
    void anEditKeepsTheCodeAndMovesTheUpdateInstantAndReplacesTheContactData() {
        GuardianView created = guardians.create(actor, requestId, "Ana", "Lopez", null, null, "ana@example.org",
                "99998888", "Casa 1", "EMAIL");
        GuardianView edited = guardians.update(actor, requestId, created.id(), "Ana", "Lopez-Perez", null, null, null,
                null, null, null, "ACTIVE");

        assertThat(edited.guardianCode()).isEqualTo("P-000001");
        assertThat(edited.lastName()).isEqualTo("Lopez-Perez");
        assertThat(edited.email()).as("a PUT replaces the whole record: an omitted e-mail is cleared").isNull();
        assertThat(edited.phone()).isNull();
        assertThat(edited.address()).isNull();
        assertThat(edited.preferredChannel()).isEqualTo("NONE");
    }

    @Test
    void anUnknownGuardianOrOneOfAnotherInstitutionIsNotFoundOnReadAndOnEdit() {
        GuardianView created = create("Ana", "Lopez", null, null);
        AuthenticatedActor stranger = actorOf(UUID.randomUUID());

        assertThatThrownBy(() -> guardians.get(actor, requestId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> guardians.get(stranger, requestId, created.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> guardians.update(stranger, requestId, created.id(), "X", "Y", null, null, null, null,
                null, null, "ACTIVE")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> guardians.update(actor, requestId, UUID.randomUUID(), "X", "Y", null, null, null, null,
                null, null, "ACTIVE")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatingAuditsOnlyTheNamesOfTheFieldsThatChanged() {
        GuardianView created = guardians.create(actor, requestId, "Nombre-Privado", "Apellido-Privado", ID, null,
                "privado@example.org", null, null, null);
        audit.clear();

        guardians.update(actor, requestId, created.id(), "Nombre-Privado", "Apellido-Cambiado", "", null,
                "otro@example.org", "99998888", null, "SMS", "INACTIVE");

        assertThat(audit).hasSize(1);
        AuditEntry entry = audit.getFirst();
        assertThat(entry.action()).isEqualTo("guardians.guardian.updated");
        assertThat(entry.entityId()).isEqualTo(created.id().toString());
        JsonNode before = json(entry.beforeValue());
        JsonNode after = json(entry.afterValue());
        assertThat(before.has("changedFields")).isFalse();
        assertThat(before.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(after.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(after.get("changedFields").asString().split(","))
                .containsExactlyInAnyOrder("lastName", "email", "phone", "preferredChannel", "status", "nationalId");
        assertThat(after.get("hasNationalId").asBoolean()).isFalse();
        assertThat(entry.beforeValue() + entry.afterValue()).doesNotContain("Privado").doesNotContain("Cambiado")
                .doesNotContain("example.org").doesNotContain("99998888").doesNotContain("0801");
    }

    // --- Inactive guardians with links ---

    @Test
    void aGuardianCanBeMadeInactiveWhileItStillHasCurrentLinksAndTheAnswerListsThem() {
        GuardianView created = create("Ana", "Lopez", null, null);
        StudentRef student = new StudentRef(UUID.randomUUID(), "E-000001", "Nombre-Alumno", "Apellido-Alumno", true);
        world.arrange(institution, student);
        world.arrange(institution, new GuardianLink(UUID.randomUUID(), created.id(), student.studentId(),
                Relationship.MOTHER, new BigDecimal("100.00"), true, true, LocalDate.of(2026, 3, 1), null,
                actor.accountId(), NOW));

        GuardianView inactive = update(created, "Ana", null, null, "INACTIVE");

        assertThat(inactive.status()).isEqualTo("INACTIVE");
        assertThat(inactive.students()).singleElement().satisfies(linked -> {
            assertThat(linked.studentCode()).isEqualTo("E-000001");
            assertThat(linked.studentFirstName()).isEqualTo("Nombre-Alumno");
            assertThat(linked.relationship()).isEqualTo("MOTHER");
            assertThat(linked.financialResponsibilityPercent()).isEqualByComparingTo("100");
            assertThat(linked.primaryPayer()).isTrue();
            assertThat(linked.billingRecipient()).isTrue();
        });
        assertThat(world.links.currentOfGuardian(institution, created.id())).hasSize(1);
        assertThat(guardians.get(actor, requestId, created.id()).students()).hasSize(1);
        assertThat(guardians.get(actor, requestId, created.id()).students().getFirst().toString())
                .doesNotContain("Nombre-Alumno");
    }

    @Test
    void theDetailOfAGuardianWithoutLinksHasAnEmptyListAndOnlyCurrentLinksAreListed() {
        GuardianView created = create("Ana", "Lopez", null, null);
        assertThat(guardians.get(actor, requestId, created.id()).students()).isEmpty();
    }

    // --- Listing ---

    private void people() {
        create("Carla", "Zelaya", null, null);
        create("Ana", "Lopez", null, null);
        create("Beto", "Lopez", null, null);
        GuardianView inactive = create("Dora", "Mejia", null, null);
        update(inactive, "Dora", null, null, "INACTIVE");
    }

    @Test
    void theListIsSortedByLastNameByDefaultAndPagedWithAServerLimit() {
        people();

        Page<GuardianView> page = guardians.list(actor, requestId, 0, 50, null, null, null);

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items()).extracting(GuardianView::lastName)
                .containsExactly("Lopez", "Lopez", "Mejia", "Zelaya");
        assertThat(page.items()).allSatisfy(view -> assertThat(view.students()).as("a list carries no students").isNull());
        assertThat(world.lastRequest.sortField()).isEqualTo("lastName");
        assertThat(world.lastRequest.ascending()).isTrue();
        assertThat(guardians.list(actor, requestId, 1, 3, null, null, null).items()).hasSize(1);
        assertThatThrownBy(() -> guardians.list(actor, requestId, 0, 201, null, null, null))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> guardians.list(actor, requestId, -1, 10, null, null, null))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> guardians.list(actor, requestId, 0, 0, null, null, null))
                .isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void onlyTheDocumentedSortFieldsAreAcceptedAndTheDirectionIsRead() {
        people();

        guardians.list(actor, requestId, 0, 10, "code,desc", null, null);
        assertThat(world.lastRequest.sortField()).isEqualTo("code");
        assertThat(world.lastRequest.ascending()).isFalse();
        guardians.list(actor, requestId, 0, 10, "status", null, null);
        assertThat(world.lastRequest.sortField()).isEqualTo("status");
        for (String bad : new String[] {"firstName", "nationalId", "lastName;drop table", "lastName,sideways",
                "lastName,asc,extra"}) {
            assertThatThrownBy(() -> guardians.list(actor, requestId, 0, 10, bad, null, null))
                    .as(bad).isInstanceOf(InvalidGuardianException.class);
        }
    }

    @Test
    void theSearchLooksInTheNamesAndTheCodeAndNeedsTwoCharacters() {
        people();

        assertThat(guardians.list(actor, requestId, 0, 10, null, "lopez", null).total()).isEqualTo(2);
        assertThat(guardians.list(actor, requestId, 0, 10, null, "P-000004", null).items())
                .extracting(GuardianView::lastName).containsExactly("Mejia");
        assertThat(guardians.list(actor, requestId, 0, 10, null, "  ", null).total()).isEqualTo(4);
        assertThat(world.lastFilter.text()).isNull();
        assertThatThrownBy(() -> guardians.list(actor, requestId, 0, 10, null, "a", null))
                .isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void theStatusFilterKeepsOnlyThatStatusAndRefusesAnUnknownOne() {
        people();

        assertThat(guardians.list(actor, requestId, 0, 10, null, null, "INACTIVE").items())
                .extracting(GuardianView::firstName).containsExactly("Dora");
        assertThat(guardians.list(actor, requestId, 0, 10, null, null, "ACTIVE").total()).isEqualTo(3);
        assertThatThrownBy(() -> guardians.list(actor, requestId, 0, 10, null, null, "DELETED"))
                .isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void anotherInstitutionNeverSeesTheGuardians() {
        people();
        AuthenticatedActor stranger = actorOf(UUID.randomUUID());

        assertThat(guardians.list(stranger, requestId, 0, 50, null, null, null).items()).isEmpty();
    }

    @Test
    void readsRunInAReadOnlyTransactionAndWritesDoNot() {
        GuardianView created = create("Ana", "Lopez", null, null);
        assertThat(readOnlyFlags).doesNotContain("true");

        guardians.get(actor, requestId, created.id());
        guardians.list(actor, requestId, 0, 10, null, null, null);

        assertThat(readOnlyFlags).contains("true");
        assertThat(transactionCalls).doesNotContain("rollback");
    }

    @Test
    void aFailedWriteIsRolledBackAndLeavesNoAuditEntry() {
        create("Ana", "Lopez", ID, null);
        audit.clear();

        assertThatThrownBy(() -> create("Eva", "Mejia", ID, null)).isInstanceOf(DuplicateNationalIdException.class);

        assertThat(transactionCalls).contains("rollback");
        assertThat(audit).isEmpty();
    }
}
