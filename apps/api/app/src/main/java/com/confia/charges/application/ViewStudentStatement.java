package com.confia.charges.application;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptLabel;
import com.confia.charges.domain.InvalidChargesValueException;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerStatementApi;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The statement of a student: what the student owes, derived at query time from the entries of its receivable
 * sub-accounts in the ledger and enriched with the charges that produced them (docs/cortes/ledger-account-statement.md).
 * It writes no financial datum. It does write one audit entry in the same transaction, because reading the finances of a
 * student, a minor, is access to personal data. {@code ledger} does not know {@code charges}: the join with the charge,
 * through the identifier of its ledger transaction, is made here.
 */
public final class ViewStudentStatement {

    /** The largest page of lines of one section. */
    public static final int MAX_PAGE_SIZE = LedgerStatementApi.MAX_PAGE_SIZE;
    public static final int DEFAULT_PAGE_SIZE = 50;

    private static final ZoneId INSTITUTION_ZONE = ZoneId.of("America/Tegucigalpa");

    private final TransactionRunner transactionRunner;
    private final StudentDirectory students;
    private final LedgerStatementApi ledger;
    private final ChargeRepository charges;
    private final PaymentConceptDirectory concepts;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ViewStudentStatement(TransactionRunner transactionRunner, StudentDirectory students,
            LedgerStatementApi ledger, ChargeRepository charges, PaymentConceptDirectory concepts,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.students = Objects.requireNonNull(students, "students");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.charges = Objects.requireNonNull(charges, "charges");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The statement of the student for the effective dates {@code from} to {@code to}, both included. A missing {@code to}
     * is today in Honduras and a missing {@code from} is twelve months before {@code to}. With a {@code currency} only
     * that section is returned (zeros when the student has no sub-account in it); without one, a section for each currency
     * the student has, none for a student with no account.
     */
    public StatementView view(AuthenticatedActor actor, String requestId, UUID studentId, CurrencyCode currency,
            LocalDate from, LocalDate to, int page, int size) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(studentId, "studentId");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(INSTITUTION_ZONE));
        LocalDate start = from != null ? from : end.minusMonths(12);
        if (start.isAfter(end) || page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidChargesValueException();
        }
        InstitutionId institution = new InstitutionId(actor.institutionId());
        // Read-write on purpose: the audit entry of the access is written in the same transaction.
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            StudentRef student = students.find(institution, studentId).orElseThrow(ResourceNotFoundException::new);
            List<CurrencyCode> currencies = currency != null ? List.of(currency)
                    : ledger.currenciesOf(institution, studentId);
            List<LedgerStatementApi.Section> raw = new ArrayList<>();
            for (CurrencyCode each : currencies) {
                raw.add(ledger.statement(institution, studentId, each, start, end, page, size));
            }
            Set<UUID> transactionIds = new HashSet<>();
            raw.forEach(section -> section.lines().forEach(line -> transactionIds.add(line.transactionId())));
            Map<UUID, ChargeRepository.StatementCharge> byTransaction =
                    charges.findByLedgerTransactions(institution, transactionIds);
            Set<UUID> conceptIds = new HashSet<>();
            byTransaction.values().forEach(charge -> conceptIds.add(charge.paymentConceptId()));
            Map<UUID, PaymentConceptLabel> labels = concepts.labels(institution, conceptIds);
            List<StatementView.Section> sections = raw.stream()
                    .map(section -> toSection(section, byTransaction, labels)).toList();
            auditLogWriter.append(ChargesAudit.statementViewed(actor, auditRequestId, studentId, currency, start, end,
                    page, size));
            return new StatementView(student.studentId(), student.studentCode(), student.firstName(),
                    student.lastName(), start, end, sections);
        });
    }

    /** The currency of the query parameter: {@code HNL} or {@code USD}, or none; anything else is a validation error. */
    public static CurrencyCode parseCurrency(String currency) {
        if (currency == null) {
            return null;
        }
        try {
            return CurrencyCode.valueOf(currency);
        } catch (IllegalArgumentException e) {
            throw new InvalidChargesValueException();
        }
    }

    private static StatementView.Section toSection(LedgerStatementApi.Section section,
            Map<UUID, ChargeRepository.StatementCharge> byTransaction, Map<UUID, PaymentConceptLabel> labels) {
        List<StatementView.Line> lines = section.lines().stream().map(line -> {
            ChargeRepository.StatementCharge charge = byTransaction.get(line.transactionId());
            StatementView.ChargeInfo info = null;
            if (charge != null) {
                PaymentConceptLabel label = labels.get(charge.paymentConceptId());
                info = new StatementView.ChargeInfo(charge.chargeId(), charge.paymentConceptId(),
                        label == null ? null : label.code(), label == null ? null : label.name(),
                        charge.conceptCategory(), charge.periodLabel(), charge.periodStart(), charge.periodEnd(),
                        charge.dueOn(), charge.origin());
            }
            return new StatementView.Line(line.transactionId(), line.kind(), line.effectiveOn(), line.postedAt(),
                    line.lineNumber(), line.reason(), line.reversesTransactionId(), line.debit(), line.credit(),
                    line.balance(), info);
        }).toList();
        return new StatementView.Section(section.currency(), section.openingBalance(), section.closingBalance(),
                section.totalLines(), section.page(), section.size(), lines);
    }
}
