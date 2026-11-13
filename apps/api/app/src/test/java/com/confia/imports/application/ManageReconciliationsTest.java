package com.confia.imports.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.imports.application.ImportBatchRepository.AppliedTotals;
import com.confia.imports.domain.ImportBatch;
import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.Reconciliation;
import com.confia.imports.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.SectionDirectory;
import com.confia.organization.application.directory.SectionNames;
import com.confia.organization.application.directory.SectionRef;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The use case of the reconciliation over in-memory ports: the counts are the server's and never the client's, the year
 * must exist in the institution, nothing is written by a preview or by a refused signing, the record and its audit entry
 * are one transaction, and neither carries anything of a person.
 */
class ManageReconciliationsTest {

    private static final Instant NOW = Instant.parse("2026-11-13T15:00:00.123456789Z");

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<String> readOnlyFlags = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls, readOnlyFlags);
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final AuthenticatedActor actor = new AuthenticatedActor(account, institutionUuid, UUID.randomUUID(),
            UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    private final String requestId = UUID.randomUUID().toString();
    private final UUID year = UUID.randomUUID();

    private final Totals batches = new Totals();
    private final Records records = new Records();
    private final Years years = new Years();
    private final Map<UUID, Integer> active = new HashMap<>();
    private final ManageReconciliations reconciliations = new ManageReconciliations(runner, batches, records, years,
            (institution, academicYearId) -> institution.value().equals(institutionUuid)
                    ? active.getOrDefault(academicYearId, 0) : 0,
            auditWriter, Clock.fixed(NOW, ZoneOffset.UTC));

    private void theYearExists() {
        years.known.add(year);
    }

    private ReconciliationView sign(String reference, int official) {
        return reconciliations.sign(actor, requestId, year, reference, official);
    }

    @Test
    void signingRecordsTheCountsTheServerComputesAndWhoSignedWhen() {
        theYearExists();
        active.put(year, 98);
        batches.totals.put(ImportKind.STUDENTS, new AppliedTotals(100, 2));
        batches.totals.put(ImportKind.GUARDIANS, new AppliedTotals(80, 0));
        batches.totals.put(ImportKind.GUARDIAN_LINKS, new AppliedTotals(95, 5));
        batches.totals.put(ImportKind.ENROLLMENTS, new AppliedTotals(98, 7));

        ReconciliationView signed = sign("Oficio 123", 100);

        assertThat(signed.academicYearId()).isEqualTo(year);
        assertThat(signed.officialReference()).isEqualTo("Oficio 123");
        assertThat(signed.officialEnrollmentCount()).isEqualTo(100);
        assertThat(signed.enrollmentsActive()).isEqualTo(98);
        assertThat(signed.difference()).isEqualTo(-2);
        assertThat(signed.students()).isEqualTo(new ReconciliationView.KindCountView(100, 2));
        assertThat(signed.guardians()).isEqualTo(new ReconciliationView.KindCountView(80, 0));
        assertThat(signed.guardianLinks()).isEqualTo(new ReconciliationView.KindCountView(95, 5));
        assertThat(signed.enrollments()).isEqualTo(new ReconciliationView.KindCountView(98, 7));
        assertThat(signed.signedBy()).isEqualTo(account);
        assertThat(signed.signedAt()).isEqualTo(Instant.parse("2026-11-13T15:00:00.123456Z"));
        assertThat(records.rows).hasSize(1);
        assertThat(records.rows.get(0).id()).isEqualTo(signed.id());
    }

    @Test
    void aKindThatWasNeverAppliedCountsZero() {
        theYearExists();
        ReconciliationView signed = sign("Oficio 1", 0);
        assertThat(signed.students()).isEqualTo(new ReconciliationView.KindCountView(0, 0));
        assertThat(signed.enrollments()).isEqualTo(new ReconciliationView.KindCountView(0, 0));
        assertThat(signed.enrollmentsActive()).isZero();
        assertThat(signed.difference()).isZero();
    }

    @Test
    void theReferenceIsTrimmedAndTheDifferenceCanBePositive() {
        theYearExists();
        active.put(year, 103);
        ReconciliationView signed = sign("  Oficio 9  ", 100);
        assertThat(signed.officialReference()).isEqualTo("Oficio 9");
        assertThat(signed.difference()).isEqualTo(3);
    }

    @Test
    void signingTwiceKeepsBothRecords() {
        theYearExists();
        ReconciliationView first = sign("Oficio 1", 10);
        ReconciliationView second = sign("Oficio 1", 10);
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(records.rows).hasSize(2);
    }

    @Test
    void aYearThatDoesNotExistInTheInstitutionIsNotFoundAndNothingIsWritten() {
        assertThatThrownBy(() -> sign("Oficio 1", 10)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(records.rows).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback").doesNotContain("commit");
    }

    @Test
    void aReferenceTheRecordRefusesNeverReachesTheStore() {
        theYearExists();
        assertThatThrownBy(() -> sign("   ", 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sign("x".repeat(101), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sign("OF\n1", 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sign("Oficio 1", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(records.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void theRecordAndItsAuditEntryAreOneTransactionAndTheEntryCarriesNoPerson() {
        theYearExists();
        active.put(year, 5);
        batches.totals.put(ImportKind.STUDENTS, new AppliedTotals(5, 1));

        ReconciliationView signed = sign("Oficio 77", 6);

        assertThat(transactionCalls).containsExactly("commit");
        assertThat(audit).hasSize(1);
        AuditEntry entry = audit.get(0);
        assertThat(entry.action()).isEqualTo("imports.reconciliation.signed");
        assertThat(entry.entityType()).isEqualTo("imports.reconciliation");
        assertThat(entry.entityId()).isEqualTo(signed.id().toString());
        assertThat(entry.actorId()).isEqualTo(account);
        assertThat(entry.institutionId()).isEqualTo(institutionUuid);
        assertThat(entry.outcome()).isEqualTo("success");
        assertThat(entry.afterValue()).contains("\"academicYearId\"", "\"officialReference\":\"Oficio 77\"",
                "\"officialEnrollmentCount\":6", "\"enrollmentsActive\":5", "\"studentsCreated\":5",
                "\"studentsRejected\":1");
    }

    @Test
    void aFailingAuditEntryRollsTheSigningBack() {
        theYearExists();
        ManageReconciliations failing = new ManageReconciliations(runner, batches, records, years,
                (institution, academicYearId) -> 0, entry -> {
                    throw new IllegalStateException("audit down");
                }, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> failing.sign(actor, requestId, year, "Oficio 1", 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(transactionCalls).contains("rollback").doesNotContain("commit");
    }

    @Test
    void aPreviewWritesNothingAndRunsReadOnly() {
        theYearExists();
        active.put(year, 12);
        batches.totals.put(ImportKind.ENROLLMENTS, new AppliedTotals(12, 3));

        ReconciliationPreview preview = reconciliations.preview(actor, requestId, year);

        assertThat(preview.academicYearId()).isEqualTo(year);
        assertThat(preview.enrollmentsActive()).isEqualTo(12);
        assertThat(preview.enrollments()).isEqualTo(new ReconciliationView.KindCountView(12, 3));
        assertThat(preview.students()).isEqualTo(new ReconciliationView.KindCountView(0, 0));
        assertThat(records.rows).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(readOnlyFlags).contains("true");
    }

    @Test
    void aPreviewOfAYearThatDoesNotExistIsNotFound() {
        assertThatThrownBy(() -> reconciliations.preview(actor, requestId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theHistoryIsNewestFirstPagedAndOfTheInstitutionOnly() {
        theYearExists();
        sign("Oficio 1", 1);
        sign("Oficio 2", 2);
        sign("Oficio 3", 3);
        records.rows.add(foreign());

        Page<ReconciliationView> first = reconciliations.list(actor, requestId, 0, 2);
        Page<ReconciliationView> second = reconciliations.list(actor, requestId, 1, 2);

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).extracting(ReconciliationView::officialReference)
                .containsExactly("Oficio 3", "Oficio 2");
        assertThat(second.items()).extracting(ReconciliationView::officialReference).containsExactly("Oficio 1");
    }

    @Test
    void thePageIsBounded() {
        assertThatThrownBy(() -> reconciliations.list(actor, requestId, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reconciliations.list(actor, requestId, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reconciliations.list(actor, requestId, 0, PageRequest.MAX_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Reconciliation foreign() {
        Reconciliation other = new Reconciliation(UUID.randomUUID(), year, "Otra institucion", 1, 1,
                new Reconciliation.KindCount(0, 0), new Reconciliation.KindCount(0, 0),
                new Reconciliation.KindCount(0, 0), new Reconciliation.KindCount(0, 0), UUID.randomUUID(), NOW);
        records.owners.put(other.id(), UUID.randomUUID());
        return other;
    }

    // --- Ports ---

    private static final class Totals implements ImportBatchRepository {

        final Map<ImportKind, AppliedTotals> totals = new EnumMap<>(ImportKind.class);

        @Override
        public void insert(InstitutionId institutionId, ImportBatch batch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ImportBatch> findValidated(InstitutionId institutionId, UUID createdBy, ImportKind kind,
                String fileSha256) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ImportBatch> latestOf(InstitutionId institutionId, UUID createdBy, ImportKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<ImportBatch> list(InstitutionId institutionId, PageRequest request, Set<ImportKind> kinds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<ImportKind, AppliedTotals> appliedTotals(InstitutionId institutionId) {
            return totals;
        }
    }

    private final class Records implements ReconciliationRepository {

        final List<Reconciliation> rows = new ArrayList<>();
        /** The institution of a row inserted by the test directly; the others belong to the institution of the actor. */
        final Map<UUID, UUID> owners = new HashMap<>();

        @Override
        public void insert(InstitutionId institutionId, Reconciliation reconciliation) {
            rows.add(reconciliation);
        }

        @Override
        public Page<Reconciliation> list(InstitutionId institutionId, PageRequest request) {
            List<Reconciliation> mine = new ArrayList<>();
            for (int i = rows.size() - 1; i >= 0; i--) {
                UUID owner = owners.getOrDefault(rows.get(i).id(), institutionUuid);
                if (owner.equals(institutionId.value())) {
                    mine.add(rows.get(i));
                }
            }
            int from = (int) Math.min(request.offset(), mine.size());
            int to = Math.min(from + request.size(), mine.size());
            return new Page<>(mine.subList(from, to), mine.size(), request.page(), request.size());
        }
    }

    private static final class Years implements SectionDirectory {

        final Set<UUID> known = new java.util.HashSet<>();

        @Override
        public boolean academicYearExists(InstitutionId institutionId, UUID academicYearId) {
            return known.contains(academicYearId);
        }

        @Override
        public Optional<SectionRef> find(InstitutionId institutionId, UUID sectionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SectionRef> lock(InstitutionId institutionId, UUID sectionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<UUID, SectionRef> findAll(InstitutionId institutionId, Collection<UUID> sectionIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> sectionIdsOfGrade(InstitutionId institutionId, UUID academicYearId, UUID gradeId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<SectionNames, List<SectionRef>> findByNames(InstitutionId institutionId,
                Collection<SectionNames> names) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UUID> activeAcademicYearId(InstitutionId institutionId) {
            throw new UnsupportedOperationException();
        }
    }
}
