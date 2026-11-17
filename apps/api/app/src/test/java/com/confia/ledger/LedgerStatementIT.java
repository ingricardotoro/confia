package com.confia.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.LedgerActor;
import com.confia.ledger.application.LedgerApi;
import com.confia.ledger.application.LedgerPosting;
import com.confia.ledger.application.LedgerPosting.PostCommand;
import com.confia.ledger.application.LedgerPosting.ReverseCommand;
import com.confia.ledger.application.LedgerQueries;
import com.confia.ledger.application.api.LedgerStatementApi;
import com.confia.ledger.domain.EntrySide;
import com.confia.ledger.domain.InvalidLedgerEntryException;
import com.confia.ledger.domain.LedgerEntry;
import com.confia.ledger.domain.TransactionKind;
import com.confia.ledger.infrastructure.JooqLedgerRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.StaffRoleFixtures;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The derived statement of a student sub-account against a real PostgreSQL (docs/cortes/ledger-account-statement.md): the
 * opening and closing balances and the running balance computed by the database from the entries, in the canonical order,
 * with the bounds of the dates, the pages, the currencies and the isolation between institutions. Accounts and amounts are
 * fictional.
 */
class LedgerStatementIT extends CommittingPostgresIntegrationTest {

    private static final LocalDate FEB = LocalDate.of(2027, 2, 1);
    private static final String REASON = "duplicated by the first run of the day";

    private record Ctx(UUID institution, UUID student, UUID otherStudent, UUID receivable, UUID income, UUID cash) {

        LedgerActor actor() {
            return new LedgerActor(institution, UUID.randomUUID(), "staff", UUID.randomUUID());
        }

        SecurityContext context() {
            return new SecurityContext(UUID.randomUUID().toString(), "staff", institution.toString(),
                    UUID.randomUUID().toString());
        }
    }

    private LedgerPosting posting() {
        return new LedgerPosting(transactionRunner(), new JooqLedgerRepository(dsl), new JooqAuditLogWriter(dsl));
    }

    private LedgerApi api() {
        return new LedgerApi(posting(), new JooqLedgerRepository(dsl));
    }

    private LedgerStatementApi.Section statement(Ctx ctx, UUID student, CurrencyCode currency, LocalDate from,
            LocalDate to, int page, int size) {
        return transactionRunner().executeReadOnly(ctx.context(),
                () -> api().statement(new InstitutionId(ctx.institution()), student, currency, from, to, page, size));
    }

    private Money balanceOf(Ctx ctx, UUID student, CurrencyCode currency, LocalDate asOf) {
        return new LedgerQueries(transactionRunner(), new JooqLedgerRepository(dsl))
                .balanceOf(ctx.actor(), student, currency, asOf, null);
    }

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    private Ctx seed() throws SQLException {
        UUID institution = UUID.randomUUID();
        Ctx ctx = new Ctx(institution, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            for (UUID student : List.of(ctx.student(), ctx.otherStudent())) {
                exec(owner, "insert into students_student (institution_id, id, student_code, first_name, last_name, "
                        + "birth_date, sex) values (?, ?, ?, 'Ana', 'Prueba', date '2015-01-01', 'F')", institution,
                        student, "E-" + student.toString().substring(0, 8));
            }
            exec(owner, "insert into ledger_account (institution_id, id, code, name, account_type, "
                    + "holds_student_balances) values (?, ?, '1105', 'Cuenta de prueba', 'ASSET', true)", institution,
                    ctx.receivable());
            exec(owner, "insert into ledger_account (institution_id, id, code, name, account_type, "
                    + "holds_student_balances) values (?, ?, '4105', 'Cuenta de prueba', 'INCOME', false)", institution,
                    ctx.income());
            exec(owner, "insert into ledger_account (institution_id, id, code, name, account_type, "
                    + "holds_student_balances) values (?, ?, '1101', 'Cuenta de prueba', 'ASSET', false)", institution,
                    ctx.cash());
            owner.commit();
        }
        return ctx;
    }

    private UUID charge(Ctx ctx, UUID student, CurrencyCode currency, LocalDate on, String key, String amount) {
        Money money = Money.of(amount, currency);
        return posting().post(ctx.actor(), new PostCommand(TransactionKind.CHARGE, on, "charges", null, null, key,
                List.of(new LedgerEntry(ctx.receivable(), student, EntrySide.DEBIT, money),
                        new LedgerEntry(ctx.income(), null, EntrySide.CREDIT, money)))).stored().transaction().id();
    }

    private UUID payment(Ctx ctx, UUID student, CurrencyCode currency, LocalDate on, String key, String amount) {
        Money money = Money.of(amount, currency);
        return posting().post(ctx.actor(), new PostCommand(TransactionKind.PAYMENT, on, "payments", null, null, key,
                List.of(new LedgerEntry(ctx.cash(), null, EntrySide.DEBIT, money),
                        new LedgerEntry(ctx.receivable(), student, EntrySide.CREDIT, money)))).stored().transaction().id();
    }

    @Test
    void aStudentWithoutAccountHasNoCurrencyAndAnEmptyZeroStatement() throws SQLException {
        Ctx ctx = seed();

        assertThat(transactionRunner().executeReadOnly(ctx.context(),
                () -> api().currenciesOf(new InstitutionId(ctx.institution()), ctx.student()))).isEmpty();
        LedgerStatementApi.Section section = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB.minusYears(1), FEB, 0, 50);

        assertThat(section.openingBalance()).isEqualTo(hnl("0"));
        assertThat(section.closingBalance()).isEqualTo(hnl("0"));
        assertThat(section.totalLines()).isZero();
        assertThat(section.lines()).isEmpty();
    }

    @Test
    void theBalanceOnADateBeforeOrAfterTheEntriesIsTheOpeningAndClosingBalance() throws SQLException {
        Ctx ctx = seed();
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB, "c1", "1650.00");
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusMonths(1), "c2", "1650.00");

        LedgerStatementApi.Section before = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB.minusMonths(6),
                FEB.minusDays(1), 0, 50);
        assertThat(before.openingBalance()).isEqualTo(hnl("0"));
        assertThat(before.closingBalance()).isEqualTo(hnl("0"));
        assertThat(before.lines()).isEmpty();

        LedgerStatementApi.Section after = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusMonths(6),
                FEB.plusMonths(7), 0, 50);
        assertThat(after.openingBalance()).isEqualTo(hnl("3300.00"));
        assertThat(after.closingBalance()).isEqualTo(hnl("3300.00"));
        assertThat(after.lines()).isEmpty();
        assertThat(after.totalLines()).isZero();
    }

    @Test
    void fromAndToAreInclusiveByEffectiveDateAndTheOpeningBalanceIsTheDayBeforeFrom() throws SQLException {
        Ctx ctx = seed();
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB.minusDays(1), "c0", "100.00");
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB, "c1", "200.00");
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusDays(10), "c2", "300.00");
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusDays(11), "c3", "400.00");

        LedgerStatementApi.Section section = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusDays(10), 0, 50);

        assertThat(section.openingBalance()).isEqualTo(hnl("100.00"));
        assertThat(section.lines()).extracting(LedgerStatementApi.Line::balance)
                .containsExactly(hnl("300.00"), hnl("600.00"));
        assertThat(section.closingBalance()).isEqualTo(hnl("600.00"));
        assertThat(section.closingBalance()).isEqualTo(balanceOf(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusDays(10)));
        assertThat(statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB, 0, 50).lines()).hasSize(1);
        assertThatThrownBy(() -> statement(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusDays(1), FEB, 0, 50))
                .isInstanceOf(InvalidLedgerEntryException.class);
    }

    @Test
    void aLateRegisteredEntryWithAnEarlierDateIsOrderedByEffectiveDateAndFixesTheRunningBalance() throws SQLException {
        Ctx ctx = seed();
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusMonths(1), "march", "300.00");
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB, "february-late", "100.00");
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB, "february-later", "50.00");

        LedgerStatementApi.Section section = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusMonths(2), 0, 50);

        assertThat(section.lines()).extracting(LedgerStatementApi.Line::effectiveOn)
                .containsExactly(FEB, FEB, FEB.plusMonths(1));
        // Same effective date: the posting order breaks the tie, so the first posted comes first.
        assertThat(section.lines()).extracting(LedgerStatementApi.Line::debit)
                .containsExactly(hnl("100.00"), hnl("50.00"), hnl("300.00"));
        assertThat(section.lines()).extracting(LedgerStatementApi.Line::balance)
                .containsExactly(hnl("100.00"), hnl("150.00"), hnl("450.00"));
    }

    @Test
    void theRunningBalanceIsExactForManyLinesWithPaymentsAndFractionsAndTheClosingBalanceIsTheLedgerBalance()
            throws SQLException {
        Ctx ctx = seed();
        String[] amounts = {"0.01", "1234.56", "7.77", "0.10", "99999.99", "3.33", "250.50"};
        List<BigDecimal> expectedBalances = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < 140; i++) {
            // Effective dates never go back, so the expected order is the posting order.
            LocalDate on = FEB.plusDays(i / 4);
            String amount = amounts[i % amounts.length];
            if (i % 5 == 4) {
                payment(ctx, ctx.student(), CurrencyCode.HNL, on, "p" + i, amount);
                running = running.subtract(new BigDecimal(amount));
            } else {
                charge(ctx, ctx.student(), CurrencyCode.HNL, on, "c" + i, amount);
                running = running.add(new BigDecimal(amount));
            }
            expectedBalances.add(running);
        }
        assertThat(expectedBalances.size()).isGreaterThan(30);

        LedgerStatementApi.Section section = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusDays(60), 0, 100);

        assertThat(section.totalLines()).isEqualTo(expectedBalances.size());
        assertThat(section.lines()).hasSize(Math.min(100, expectedBalances.size()));
        for (int i = 0; i < section.lines().size(); i++) {
            assertThat(section.lines().get(i).balance().amount()).as("line %d", i)
                    .isEqualByComparingTo(expectedBalances.get(i));
        }
        LedgerStatementApi.Section last = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusDays(60), 0,
                100);
        assertThat(last.closingBalance().amount()).isEqualByComparingTo(running);
        assertThat(last.closingBalance()).isEqualTo(balanceOf(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusDays(60)));
        assertThat(section.lines().get(0).debit()).isEqualTo(hnl("0.01"));
        assertThat(section.lines().get(0).credit()).isEqualTo(hnl("0"));
    }

    @Test
    void pagesAreStableDisjointAndTogetherEqualToTheWholeList() throws SQLException {
        Ctx ctx = seed();
        for (int i = 0; i < 23; i++) {
            charge(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusDays(i / 3), "c" + i, (i + 1) + ".00");
        }

        List<LedgerStatementApi.Line> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            LedgerStatementApi.Section section = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB,
                    FEB.plusDays(30), page, 10);
            assertThat(section.totalLines()).isEqualTo(23);
            assertThat(section.page()).isEqualTo(page);
            paged.addAll(section.lines());
        }
        List<LedgerStatementApi.Line> whole = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusDays(30), 0,
                100).lines();

        assertThat(paged).hasSize(23).isEqualTo(whole);
        assertThat(paged).extracting(LedgerStatementApi.Line::transactionId).doesNotHaveDuplicates();
        assertThat(statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusDays(30), 3, 10).lines()).isEmpty();
        // A page beyond the end still carries the balances and the count.
        LedgerStatementApi.Section beyond = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusDays(30), 9, 10);
        assertThat(beyond.totalLines()).isEqualTo(23);
        assertThat(beyond.closingBalance()).isEqualTo(hnl("276.00"));
        // A second page starts from the balance of the whole history before it, not from zero.
        assertThat(statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusDays(30), 1, 10).lines().get(0).balance())
                .isEqualTo(hnl("66.00"));
        assertThatThrownBy(() -> statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB, -1, 10))
                .isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB, 0, 101))
                .isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB, 0, 0))
                .isInstanceOf(InvalidLedgerEntryException.class);
    }

    @Test
    void aReversedChargeNetsToZeroAndBothLinesShowTheirKindAndTheReason() throws SQLException {
        Ctx ctx = seed();
        UUID original = charge(ctx, ctx.student(), CurrencyCode.HNL, FEB, "c1", "1650.00");
        UUID reversal = posting().reverse(ctx.actor(), new ReverseCommand(original, "charges", FEB.plusDays(3), REASON,
                "charges:void:1")).stored().transaction().id();

        LedgerStatementApi.Section section = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusDays(10), 0, 50);

        assertThat(section.lines()).hasSize(2);
        LedgerStatementApi.Line first = section.lines().get(0);
        LedgerStatementApi.Line second = section.lines().get(1);
        assertThat(first.transactionId()).isEqualTo(original);
        assertThat(first.kind()).isEqualTo("CHARGE");
        assertThat(first.reason()).isNull();
        assertThat(first.balance()).isEqualTo(hnl("1650.00"));
        assertThat(second.transactionId()).isEqualTo(reversal);
        assertThat(second.kind()).isEqualTo("REVERSAL");
        assertThat(second.reason()).isEqualTo(REASON);
        assertThat(second.reversesTransactionId()).isEqualTo(original);
        assertThat(second.credit()).isEqualTo(hnl("1650.00"));
        assertThat(second.debit()).isEqualTo(hnl("0"));
        assertThat(second.balance()).isEqualTo(hnl("0"));
        assertThat(section.closingBalance()).isEqualTo(hnl("0"));
    }

    @Test
    void aCreditBalanceIsNegative() throws SQLException {
        Ctx ctx = seed();
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB, "c1", "100.00");
        payment(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusDays(1), "p1", "130.50");

        LedgerStatementApi.Section section = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusDays(5), 0, 50);

        assertThat(section.closingBalance()).isEqualTo(hnl("-30.50"));
        assertThat(section.lines().get(1).balance()).isEqualTo(hnl("-30.50"));
        assertThat(section.lines().get(1).kind()).isEqualTo("PAYMENT");
    }

    @Test
    void twoCurrenciesAreTwoIndependentSectionsListedAlphabetically() throws SQLException {
        Ctx ctx = seed();
        charge(ctx, ctx.student(), CurrencyCode.USD, FEB, "usd1", "10.00");
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB, "hnl1", "250.00");
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB.plusDays(1), "hnl2", "250.00");

        assertThat(transactionRunner().executeReadOnly(ctx.context(),
                () -> api().currenciesOf(new InstitutionId(ctx.institution()), ctx.student())))
                        .containsExactly(CurrencyCode.HNL, CurrencyCode.USD);
        LedgerStatementApi.Section hnl = statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB.plusDays(5), 0, 50);
        LedgerStatementApi.Section usd = statement(ctx, ctx.student(), CurrencyCode.USD, FEB, FEB.plusDays(5), 0, 50);

        assertThat(hnl.closingBalance()).isEqualTo(hnl("500.00"));
        assertThat(hnl.totalLines()).isEqualTo(2);
        assertThat(usd.closingBalance()).isEqualTo(Money.of("10.00", CurrencyCode.USD));
        assertThat(usd.lines()).hasSize(1);
        assertThat(usd.lines().get(0).balance().currency()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    void anotherStudentOfTheSameInstitutionAndAnotherInstitutionNeverLeak() throws SQLException {
        Ctx ctx = seed();
        Ctx stranger = seed();
        charge(ctx, ctx.student(), CurrencyCode.HNL, FEB, "c1", "100.00");
        charge(ctx, ctx.otherStudent(), CurrencyCode.HNL, FEB, "c2", "7.00");
        charge(stranger, stranger.student(), CurrencyCode.HNL, FEB, "s1", "9999.00");

        assertThat(statement(ctx, ctx.student(), CurrencyCode.HNL, FEB, FEB, 0, 50).closingBalance())
                .isEqualTo(hnl("100.00"));
        assertThat(statement(ctx, ctx.otherStudent(), CurrencyCode.HNL, FEB, FEB, 0, 50).closingBalance())
                .isEqualTo(hnl("7.00"));
        // Asking for a student of another institution inside this one gives zeros and no lines (row-level security).
        LedgerStatementApi.Section foreign = statement(ctx, stranger.student(), CurrencyCode.HNL, FEB, FEB, 0, 50);
        assertThat(foreign.lines()).isEmpty();
        assertThat(foreign.closingBalance()).isEqualTo(hnl("0"));
        assertThat(transactionRunner().executeReadOnly(ctx.context(),
                () -> api().currenciesOf(new InstitutionId(ctx.institution()), stranger.student()))).isEmpty();
    }
}
