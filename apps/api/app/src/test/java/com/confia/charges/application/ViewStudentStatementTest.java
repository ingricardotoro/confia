package com.confia.charges.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptLabel;
import com.confia.catalog.application.directory.PaymentConceptRef;
import com.confia.charges.domain.ComputedCharge;
import com.confia.charges.domain.InvalidChargesValueException;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.api.LedgerStatementApi;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import com.confia.support.JdbcStubTransactions;
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
import org.junit.jupiter.api.Test;

/**
 * The composition of the statement of a student (docs/cortes/ledger-account-statement.md) over in-memory ports: the join
 * with the charge and the concept, the line that is not a charge, the sections by currency, the defaults of the dates, the
 * validation, the 404 of a student of another institution and the audit entry. The SQL (the balances, the running balance
 * and the order) is proven against PostgreSQL in {@code ViewStudentStatementIT}.
 */
class ViewStudentStatementTest {

    private static final Instant NOW = Instant.parse("2027-03-01T03:00:00Z");

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), institutionUuid,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    private final String requestId = UUID.randomUUID().toString();
    private final UUID studentId = UUID.randomUUID();
    private final UUID conceptId = UUID.randomUUID();
    private final UUID chargeTransaction = UUID.randomUUID();
    private final UUID paymentTransaction = UUID.randomUUID();

    private final FakeLedger ledger = new FakeLedger();
    private final FakeCharges charges = new FakeCharges();
    private final ViewStudentStatement service = new ViewStudentStatement(
            JdbcStubTransactions.runner(transactionCalls), new FakeStudents(), ledger, charges, new FakeConcepts(),
            auditWriter, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private LedgerStatementApi.Line line(UUID transaction, String kind, String debit, String credit, String balance) {
        return new LedgerStatementApi.Line(transaction, kind, LocalDate.parse("2027-02-01"), NOW, 1, "charges",
                "PAYMENT".equals(kind) ? "payment of february" : null, null, hnl(debit), hnl(credit), hnl(balance));
    }

    @Test
    void aChargeLineCarriesItsConceptPeriodAndDueDateAndALineThatIsNotAChargeCarriesOnlyItsKindAndReason() {
        ledger.currencies = List.of(CurrencyCode.HNL);
        ledger.sections.put(CurrencyCode.HNL, new LedgerStatementApi.Section(CurrencyCode.HNL, hnl("0"), hnl("1000.00"),
                2, 0, 50, List.of(line(chargeTransaction, "CHARGE", "1650.00", "0", "1650.00"),
                        line(paymentTransaction, "PAYMENT", "0", "650.00", "1000.00"))));
        UUID chargeId = UUID.randomUUID();
        charges.byTransaction.put(chargeTransaction, new ChargeRepository.StatementCharge(chargeId, conceptId, "TUITION",
                "2027-02", LocalDate.parse("2027-02-01"), LocalDate.parse("2027-02-28"), LocalDate.parse("2027-02-05"),
                "AUTOMATIC"));

        StatementView view = service.view(actor, requestId, studentId, null, LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-02-28"), 0, 50);

        assertThat(view.sections()).hasSize(1);
        StatementView.Section section = view.sections().get(0);
        assertThat(section.closingBalance()).isEqualTo(hnl("1000.00"));
        StatementView.Line first = section.lines().get(0);
        assertThat(first.charge().chargeId()).isEqualTo(chargeId);
        assertThat(first.charge().conceptCode()).isEqualTo("COL");
        assertThat(first.charge().conceptName()).isEqualTo("Colegiatura");
        assertThat(first.charge().periodLabel()).isEqualTo("2027-02");
        assertThat(first.charge().dueOn()).isEqualTo(LocalDate.parse("2027-02-05"));
        StatementView.Line second = section.lines().get(1);
        assertThat(second.charge()).isNull();
        assertThat(second.kind()).isEqualTo("PAYMENT");
        assertThat(second.reason()).isEqualTo("payment of february");
        assertThat(view.studentCode()).isEqualTo("E-1");
    }

    @Test
    void aStudentWithoutAccountGetsNoSectionAndOneAskedCurrencyGetsAZeroSection() {
        StatementView none = service.view(actor, requestId, studentId, null, null, null, 0, 50);
        assertThat(none.sections()).isEmpty();

        StatementView asked = service.view(actor, requestId, studentId, CurrencyCode.USD, null, null, 0, 50);
        assertThat(asked.sections()).hasSize(1);
        assertThat(asked.sections().get(0).currency()).isEqualTo(CurrencyCode.USD);
        assertThat(asked.sections().get(0).openingBalance().isZero()).isTrue();
        assertThat(asked.sections().get(0).lines()).isEmpty();
    }

    @Test
    void withoutCurrencyThereIsOneSectionForEachCurrencyTheStudentHas() {
        ledger.currencies = List.of(CurrencyCode.HNL, CurrencyCode.USD);
        StatementView view = service.view(actor, requestId, studentId, null, null, null, 0, 50);
        assertThat(view.sections()).extracting(StatementView.Section::currency)
                .containsExactly(CurrencyCode.HNL, CurrencyCode.USD);
    }

    @Test
    void theDefaultPeriodIsTheLastTwelveMonthsEndingTodayInHonduras() {
        // 2027-03-01T03:00Z is still 2027-02-28 in America/Tegucigalpa (UTC-6).
        StatementView view = service.view(actor, requestId, studentId, null, null, null, 0, 50);
        assertThat(view.to()).isEqualTo(LocalDate.parse("2027-02-28"));
        assertThat(view.from()).isEqualTo(LocalDate.parse("2026-02-28"));
    }

    @Test
    void aStudentOfAnotherInstitutionOrUnknownIsNotFoundAndLeavesNoAuditEntry() {
        assertThatThrownBy(() -> service.view(actor, requestId, UUID.randomUUID(), null, null, null, 0, 50))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(audit).isEmpty();
    }

    @Test
    void invalidRangesAndPagesAreRejectedBeforeAnythingIsRead() {
        LocalDate from = LocalDate.parse("2027-02-01");
        assertThatThrownBy(() -> service.view(actor, requestId, studentId, null, from, from.minusDays(1), 0, 50))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.view(actor, requestId, studentId, null, from, from, -1, 50))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.view(actor, requestId, studentId, null, from, from, 0, 0))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.view(actor, requestId, studentId, null, from, from, 0, 101))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThat(audit).isEmpty();
        assertThat(service.view(actor, requestId, studentId, null, from, from, 0, 100).from()).isEqualTo(from);
    }

    @Test
    void everyAcceptedReadLeavesOneAuditEntryWithTheFiltersAndNoAmountOrName() {
        service.view(actor, requestId, studentId, CurrencyCode.HNL, LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-02-28"), 2, 25);

        assertThat(audit).hasSize(1);
        AuditEntry entry = audit.get(0);
        assertThat(entry.action()).isEqualTo("charges.statement.viewed");
        assertThat(entry.entityType()).isEqualTo("charges.statement");
        assertThat(entry.entityId()).isEqualTo(studentId.toString());
        assertThat(entry.outcome()).isEqualTo("success");
        assertThat(entry.actorId()).isEqualTo(actor.accountId());
        assertThat(entry.afterValue()).contains("\"currency\":\"HNL\"", "\"from\":\"2027-01-01\"",
                "\"to\":\"2027-02-28\"", "\"page\":2", "\"size\":25");
        assertThat(entry.afterValue()).doesNotContain("Prueba").doesNotContain("Ana");
        assertThat(transactionCalls).contains("commit");
    }

    // --- In-memory ports ---

    private final class FakeStudents implements StudentDirectory {

        @Override
        public Optional<StudentRef> find(InstitutionId institutionId, UUID id) {
            return institutionId.equals(institution) && id.equals(studentId)
                    ? Optional.of(new StudentRef(studentId, "E-1", "Ana", "Prueba", true)) : Optional.empty();
        }

        @Override
        public Optional<StudentRef> lock(InstitutionId institutionId, UUID id) {
            return find(institutionId, id);
        }

        @Override
        public Map<UUID, StudentRef> findAll(InstitutionId institutionId, Collection<UUID> ids) {
            return Map.of();
        }

        @Override
        public Map<String, StudentRef> findByExternalRefs(InstitutionId institutionId, Collection<String> refs) {
            return Map.of();
        }
    }

    private static final class FakeLedger implements LedgerStatementApi {

        List<CurrencyCode> currencies = List.of();
        final Map<CurrencyCode, Section> sections = new HashMap<>();

        @Override
        public List<CurrencyCode> currenciesOf(InstitutionId institutionId, UUID studentId) {
            return currencies;
        }

        @Override
        public Section statement(InstitutionId institutionId, UUID studentId, CurrencyCode currency, LocalDate from,
                LocalDate to, int page, int size) {
            Section found = sections.get(currency);
            return found != null ? found : new Section(currency, Money.zero(currency), Money.zero(currency), 0, page,
                    size, List.of());
        }
    }

    private static final class FakeCharges implements ChargeRepository {

        final Map<UUID, StatementCharge> byTransaction = new HashMap<>();

        @Override
        public Map<UUID, StatementCharge> findByLedgerTransactions(InstitutionId institution,
                Collection<UUID> ledgerTransactionIds) {
            Map<UUID, StatementCharge> found = new HashMap<>();
            ledgerTransactionIds.forEach(id -> {
                if (byTransaction.containsKey(id)) {
                    found.put(id, byTransaction.get(id));
                }
            });
            return found;
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
    }

    private final class FakeConcepts implements PaymentConceptDirectory {

        @Override
        public Optional<PaymentConceptRef> lockShare(InstitutionId institutionId, UUID paymentConceptId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<UUID, PaymentConceptLabel> labels(InstitutionId institutionId, Collection<UUID> ids) {
            return ids.contains(conceptId) ? Map.of(conceptId, new PaymentConceptLabel(conceptId, "COL", "Colegiatura"))
                    : Map.of();
        }
    }
}
