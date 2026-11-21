package com.confia.charges.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.charges.domain.ComputedCharge;
import com.confia.charges.domain.IntegrityCheck;
import com.confia.charges.domain.IntegrityDiscrepanciesFoundException;
import com.confia.charges.domain.IntegrityFinding;
import com.confia.charges.domain.IntegrityKind;
import com.confia.charges.domain.IntegrityRun;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.api.LedgerIntegrityApi;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The nightly integrity check with the ports replaced by fakes: what it records when the data is clean, when a discrepancy
 * persists and when it was only an operation that committed between the reads of the first reading, what it audits, and that
 * the schedule is the one declared. The SQL and the real ledger are proven against PostgreSQL in {@code ChargesIntegrityIT}.
 */
class RunChargesIntegrityCheckTest {

    private static final Instant NOW = Instant.parse("2027-02-11T08:30:00Z");

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<String> readOnlyFlags = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final AuditLogWriter auditWriter = audit::add;
    private final InstitutionId institution = new InstitutionId(UUID.randomUUID());
    private final RecurringRun run = new RecurringRun(RunChargesIntegrityCheck.TASK_NAME, UUID.randomUUID(), NOW);

    private final FakeCharges charges = new FakeCharges();
    private final FakeLedger ledger = new FakeLedger();
    private final FakeRuns runs = new FakeRuns();
    private final RunChargesIntegrityCheck check = new RunChargesIntegrityCheck(
            JdbcStubTransactions.runner(transactionCalls, readOnlyFlags), charges, ledger, runs, auditWriter,
            new OperationalEventLog(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    /** One charge with its transaction and no reversal: the data is sound. */
    private void soundData() {
        UUID chargeId = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        charges.facts = () -> List.of(new IntegrityCheck.ChargeFact(chargeId, transaction, hnl("1650.00"), null));
        ledger.postings = () -> List.of(new LedgerIntegrityApi.SourcePosting(transaction, "CHARGE", chargeId, null,
                hnl("1650.00"), hnl("1650.00")));
        ledger.movements = () -> List.of(new LedgerIntegrityApi.ReceivableMovement(CurrencyCode.HNL, hnl("1650.00"),
                hnl("0.00")));
    }

    private static LedgerIntegrityApi.SourcePosting orphan(UUID transaction) {
        return new LedgerIntegrityApi.SourcePosting(transaction, "CHARGE", UUID.randomUUID(), null, hnl("10.00"),
                hnl("10.00"));
    }

    @Test
    void cleanDataIsRecordedAsOkWithItsCountsAndAnAuditEntryAndRaisesNothing() {
        soundData();

        check.runFor(institution, run);

        assertThat(runs.inserted).singleElement().satisfies(stored -> {
            assertThat(stored.id()).isEqualTo(run.runId());
            assertThat(stored.status()).isEqualTo("OK");
            assertThat(stored.chargesChecked()).isEqualTo(1);
            assertThat(stored.postingsChecked()).isEqualTo(1);
            assertThat(stored.startedAt()).isEqualTo(NOW);
        });
        assertThat(runs.findings).isEmpty();
        assertThat(charges.readings).as("a sound first reading is not repeated").isEqualTo(1);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("charges.integrity_run.completed");
            assertThat(entry.entityId()).isEqualTo(run.runId().toString());
            assertThat(entry.actorKind()).isEqualTo("system");
            assertThat(entry.outcome()).isEqualTo("success");
            assertThat(entry.institutionId()).isEqualTo(institution.value());
            assertThat(entry.afterValue()).contains("\"status\":\"OK\"", "\"discrepancyCount\":0");
        });
        assertThat(readOnlyFlags.stream().filter("true"::equals).count())
                .as("the reading is the only read-only transaction, the record is not").isEqualTo(1);
        assertThat(transactionCalls).containsExactly("commit", "commit");
    }

    @Test
    void aDiscrepancyThatBothReadingsSeeIsRecordedAuditedAsAnErrorAndRaisedSoTheNightStaysVisible() {
        soundData();
        UUID stray = UUID.randomUUID();
        List<LedgerIntegrityApi.SourcePosting> sound = ledger.postings.get();
        ledger.postings = () -> {
            List<LedgerIntegrityApi.SourcePosting> all = new ArrayList<>(sound);
            all.add(orphan(stray));
            return all;
        };
        ledger.movements = () -> List.of(new LedgerIntegrityApi.ReceivableMovement(CurrencyCode.HNL, hnl("1660.00"),
                hnl("0.00")));

        assertThatThrownBy(() -> check.runFor(institution, run)).isInstanceOfSatisfying(
                IntegrityDiscrepanciesFoundException.class, found -> {
                    assertThat(found.institution()).isEqualTo(institution);
                    assertThat(found.discrepancies()).isEqualTo(2);
                });

        assertThat(charges.readings).as("a first reading with findings is repeated once").isEqualTo(2);
        assertThat(runs.inserted).singleElement().satisfies(stored -> {
            assertThat(stored.status()).isEqualTo("DISCREPANCIES");
            assertThat(stored.discrepancyCount()).isEqualTo(2);
        });
        assertThat(runs.findings).extracting(IntegrityFinding::kind)
                .containsExactly(IntegrityKind.ORPHAN_POSTING, IntegrityKind.RECEIVABLE_MISMATCH);
        assertThat(runs.findings.get(0).ledgerTransactionId()).isEqualTo(stray);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo("error");
            assertThat(entry.afterValue()).contains("\"status\":\"DISCREPANCIES\"", "\"orphanPosting\":1",
                    "\"receivableMismatch\":1");
        });
    }

    @Test
    void aDiscrepancyOnlyTheFirstReadingSawWasAnOperationBetweenItsReadsAndIsNotRecorded() {
        soundData();
        List<LedgerIntegrityApi.SourcePosting> sound = ledger.postings.get();
        int[] reading = {0};
        ledger.postings = () -> {
            List<LedgerIntegrityApi.SourcePosting> all = new ArrayList<>(sound);
            if (reading[0]++ == 0) {
                all.add(orphan(UUID.randomUUID()));
            }
            return all;
        };

        check.runFor(institution, run);

        assertThat(charges.readings).isEqualTo(2);
        assertThat(runs.inserted).singleElement().satisfies(stored -> assertThat(stored.status()).isEqualTo("OK"));
        assertThat(runs.findings).isEmpty();
        assertThat(audit).singleElement().satisfies(entry -> assertThat(entry.outcome()).isEqualTo("success"));
    }

    @Test
    void anInstitutionWithoutChargesIsSoundWithZeroCounts() {
        check.runFor(institution, run);

        assertThat(runs.inserted).singleElement().satisfies(stored -> {
            assertThat(stored.status()).isEqualTo("OK");
            assertThat(stored.chargesChecked()).isZero();
            assertThat(stored.postingsChecked()).isZero();
        });
    }

    @Test
    void whenAReaderFailsNothingIsRecordedAndTheFailureReachesTheJobRunner() {
        soundData();
        ledger.postings = () -> {
            throw new IllegalStateException("the database went away");
        };

        assertThatThrownBy(() -> check.runFor(institution, run)).isInstanceOf(IllegalStateException.class);

        assertThat(runs.inserted).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void theJobRunsEveryDayAtHalfPastTwoUnderItsOwnNameAndCarriesNoData() {
        RecurringJob job = check.job();

        assertThat(job.name()).isEqualTo("charges.integrity-check").isEqualTo(RunChargesIntegrityCheck.TASK_NAME);
        assertThat(job.calendar()).isEqualTo(new JobCalendar.DailyAt(LocalTime.of(2, 30)));
        assertThat(job.handler()).isSameAs(check);
    }

    // --- Fakes ---

    private static final class FakeCharges implements ChargeRepository {

        java.util.function.Supplier<List<IntegrityCheck.ChargeFact>> facts = List::of;
        int readings;

        @Override
        public List<IntegrityCheck.ChargeFact> integrityFacts(InstitutionId institution) {
            readings++;
            return facts.get();
        }

        @Override
        public List<ExistingCharge> findOverlapping(InstitutionId institution, UUID studentId, UUID paymentConceptId,
                LocalDate periodStart, LocalDate periodEnd) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertAutomatic(InstitutionId institution, UUID chargeId, ComputedCharge charge, UUID runId,
                UUID ledgerTransactionId, UUID createdBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CurrencyTotals> totalsOfRun(InstitutionId institution, UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<UUID, StatementCharge> findByLedgerTransactions(InstitutionId institution,
                Collection<UUID> ledgerTransactionIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChargeLabelRow> findLabels(InstitutionId institution, java.util.Collection<UUID> chargeIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChargeForPayment> findActiveByStudent(InstitutionId institution, UUID studentId,
                com.confia.kernel.CurrencyCode currency) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ChargeForVoid> findForVoid(InstitutionId institution, UUID chargeId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<ChargeRow> listByStudent(InstitutionId institution, UUID studentId, ChargeFilter filter,
                PageRequest page) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeLedger implements LedgerIntegrityApi {

        java.util.function.Supplier<List<SourcePosting>> postings = List::of;
        java.util.function.Supplier<List<ReceivableMovement>> movements = List::of;

        @Override
        public List<SourcePosting> postingsOfSource(InstitutionId institutionId, String sourceModule) {
            assertThat(sourceModule).isEqualTo("charges");
            return postings.get();
        }

        @Override
        public List<UnbalancedTransaction> unbalancedTransactions(InstitutionId institutionId) {
            return List.of();
        }

        @Override
        public List<ReceivableMovement> receivableMovementOfSource(InstitutionId institutionId, String sourceModule,
                Collection<String> kinds) {
            assertThat(sourceModule).isEqualTo("charges");
            assertThat(kinds).containsExactlyInAnyOrder("CHARGE", "REVERSAL");
            return movements.get();
        }
    }

    private static final class FakeRuns implements IntegrityRunRepository {

        final List<IntegrityRun> inserted = new ArrayList<>();
        final List<IntegrityFinding> findings = new ArrayList<>();

        @Override
        public void insert(InstitutionId institution, IntegrityRun run, List<IntegrityFinding> found) {
            inserted.add(run);
            findings.addAll(found);
        }

        @Override
        public Page<IntegrityRun> list(InstitutionId institution, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IntegrityRun> latest(InstitutionId institution) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IntegrityRun> findById(InstitutionId institution, UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IntegrityFinding> findings(InstitutionId institution, UUID runId) {
            throw new UnsupportedOperationException();
        }
    }
}
