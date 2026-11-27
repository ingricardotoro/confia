package com.confia.charges;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.charges.application.ChargeFilter;
import com.confia.charges.application.PageRequest;
import com.confia.charges.application.AppliedBenefitView;
import com.confia.charges.infrastructure.JooqChargeRepository;
import com.confia.kernel.InstitutionId;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/**
 * V40 only ADDS to the charges (docs/cortes/scholarships-discounts.md): a database that holds charges as V37 and V38 wrote them (a charge
 * without a benefit, and a charge with the benefit of one scholarship and no benefit lines, because the lines did not exist) migrates
 * to V40 with every row unchanged, the new constraints accept those rows and the engine reads them as before: what the award gave in
 * the period, and the scholarship as the only benefit of the charge. The database is a throw-away one of the shared container, migrated
 * by the real migrations in two steps; the legacy rows are written by the superuser with the triggers off, since the migrations of
 * that time are the ones that guarantee them and nothing can write them any more. Everything is fictional.
 */
class ChargeBenefitsMigrationIT {

    private static final String PASSWORD = "test-only-not-a-secret";
    private static final LocalDate FEB = LocalDate.parse("2027-02-01");

    private static final UUID INSTITUTION = UUID.randomUUID();
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID AWARD = UUID.randomUUID();

    private record Database(String name, String url) {
    }

    private static Database create() throws SQLException {
        String name = "v40_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = SharedPostgresContainer.connectionAs("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " OWNER confia_owner");
        }
        return new Database(name, SharedPostgresContainer.jdbcUrl().replace("/confia_test", "/" + name));
    }

    private static void drop(Database database) throws SQLException {
        try (Connection admin = SharedPostgresContainer.connectionAs("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database.name() + " WITH (FORCE)");
        }
    }

    private static void migrate(Database database, String target) {
        var configuration = Flyway.configure().dataSource(database.url(), "confia_owner", PASSWORD)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static Connection superuser(Database database) throws SQLException {
        return DriverManager.getConnection(database.url(), "postgres", PASSWORD);
    }

    /** A charge as the earlier migrations allowed it; the columns the checks look at are consistent, the references are not needed. */
    private static void insertLegacyCharge(Connection c, UUID id, LocalDate periodStart, String discount, String tax, String total,
            boolean withAward) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement("""
                insert into charges_charge (institution_id, id, student_id, enrollment_id, payment_concept_id, concept_category,
                    plan_id, plan_item_id, accrual_run_id, origin, period_start, period_end, period_label, pricing_date, due_on,
                    price_list_item_id, base_amount, base_currency, discount_amount, discount_currency, tax_amount, tax_currency,
                    total_amount, total_currency, is_taxable, tax_rate_id, tax_rate_period_id, tax_rate_percent,
                    ledger_transaction_id, created_by, scholarship_award_id, scholarship_code)
                values (?, ?, ?, ?, ?, 'TUITION', ?, ?, ?, 'AUTOMATIC', ?, ?, to_char(?::date, 'YYYY-MM'), ?, ?, ?, 1100.00, 'HNL',
                    ?::numeric, 'HNL', ?::numeric, 'HNL', ?::numeric, 'HNL', true, ?, ?, 15, ?, ?, ?, ?)
                """)) {
            insert.setObject(1, INSTITUTION);
            insert.setObject(2, id);
            insert.setObject(3, STUDENT);
            insert.setObject(4, UUID.randomUUID());
            insert.setObject(5, UUID.randomUUID());
            insert.setObject(6, UUID.randomUUID());
            insert.setObject(7, UUID.randomUUID());
            insert.setObject(8, UUID.randomUUID());
            insert.setObject(9, periodStart);
            insert.setObject(10, periodStart.plusDays(27));
            insert.setObject(11, periodStart);
            insert.setObject(12, periodStart);
            insert.setObject(13, periodStart.plusDays(9));
            insert.setObject(14, UUID.randomUUID());
            insert.setString(15, discount);
            insert.setString(16, tax);
            insert.setString(17, total);
            insert.setObject(18, UUID.randomUUID());
            insert.setObject(19, UUID.randomUUID());
            insert.setObject(20, UUID.randomUUID());
            insert.setObject(21, UUID.randomUUID());
            insert.setObject(22, withAward ? AWARD : null);
            insert.setString(23, withAward ? "BECA-25" : null);
            insert.executeUpdate();
        }
    }

    private static Connection admin(Database database) throws SQLException {
        Connection c = DriverManager.getConnection(database.url(), "confia_admin_app", PASSWORD);
        try (PreparedStatement set = c.prepareStatement("select set_config('app.institution_id', ?, false)")) {
            set.setString(1, INSTITUTION.toString());
            set.execute();
        }
        return c;
    }

    @Test
    void chargesOfTheEarlierMigrationsSurviveV40UnchangedAndAreReadAsBefore() throws SQLException {
        Database database = create();
        try {
            migrate(database, "39");
            UUID withAward = UUID.randomUUID();
            UUID plain = UUID.randomUUID();
            UUID march = UUID.randomUUID();
            try (Connection c = superuser(database); Statement s = c.createStatement()) {
                s.execute("set session_replication_role = replica");
                // 1,100.00 with a 25 % scholarship (275.00) and 15 % of tax on 825.00 (123.75): 948.75.
                insertLegacyCharge(c, withAward, FEB, "275.00", "123.75", "948.75", true);
                // The same award in another period, and a charge with no benefit at all.
                insertLegacyCharge(c, march, FEB.plusMonths(1), "275.00", "123.75", "948.75", true);
                insertLegacyCharge(c, plain, FEB.plusMonths(2), "0.00", "165.00", "1265.00", false);
            }

            migrate(database, null);

            try (Connection c = superuser(database); Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("select count(*), sum(discount_amount), sum(total_amount), "
                        + "count(scholarship_award_id) from charges_charge")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(3);
                    assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("550.00");
                    assertThat(rs.getBigDecimal(3)).isEqualByComparingTo("3162.50");
                    assertThat(rs.getInt(4)).isEqualTo(2);
                }
                try (ResultSet rs = s.executeQuery("select count(*) from charges_charge_benefit")) {
                    rs.next();
                    // Nothing was written for them: no row was added, none was changed.
                    assertThat(rs.getInt(1)).isZero();
                }
            }
            try (Connection c = admin(database)) {
                DSLContext dsl = DSL.using(c, SQLDialect.POSTGRES);
                JooqChargeRepository charges = new JooqChargeRepository(dsl);
                // What the award gave in the period still counts against its cap: the whole benefit of a charge without lines.
                assertThat(charges.benefitGiven(new InstitutionId(INSTITUTION), AWARD, FEB).amount())
                        .isEqualByComparingTo("275.00");
                assertThat(charges.benefitGiven(new InstitutionId(INSTITUTION), AWARD, FEB.plusMonths(2)).amount())
                        .isEqualByComparingTo("0.00");
                try (PreparedStatement given = c.prepareStatement("select charges_award_benefit_given(?, ?, ?, ?)")) {
                    given.setObject(1, INSTITUTION);
                    given.setObject(2, AWARD);
                    given.setObject(3, FEB);
                    given.setObject(4, UUID.randomUUID());
                    try (ResultSet rs = given.executeQuery()) {
                        rs.next();
                        assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("275.00");
                    }
                    given.setObject(4, withAward);
                    try (ResultSet rs = given.executeQuery()) {
                        rs.next();
                        assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("0.00");
                    }
                }
                // The student's charges show the scholarship of a legacy charge as its only benefit, and none for a plain charge.
                var page = charges.listByStudent(new InstitutionId(INSTITUTION), STUDENT, new ChargeFilter(null, null, null, false),
                        new PageRequest(0, 50, "periodStart", true));
                assertThat(page.items()).hasSize(3);
                var first = page.items().get(0);
                assertThat(first.id()).isEqualTo(withAward);
                assertThat(first.benefits()).singleElement().satisfies(benefit -> {
                    assertThat(benefit.source()).isEqualTo("SCHOLARSHIP");
                    assertThat(benefit.awardId()).isEqualTo(AWARD);
                    assertThat(benefit.scholarshipCode()).isEqualTo("BECA-25");
                    assertThat(benefit.amount().amount()).isEqualByComparingTo("275.00");
                });
                assertThat(page.items().get(2).id()).isEqualTo(plain);
                assertThat(page.items().get(2).benefits()).isEmpty();
            }
        } finally {
            drop(database);
        }
    }

    @Test
    void theRelaxedCheckAcceptsABenefitWithoutAnAwardAndStillRefusesAnAwardWithoutBenefit() throws SQLException {
        Database database = create();
        try {
            migrate(database, null);
            try (Connection c = superuser(database); Statement s = c.createStatement()) {
                s.execute("set session_replication_role = replica");
                // Only discounts reduced this charge: a benefit of 100.00 and no scholarship (net 1,000.00, tax 150.00, total 1,150.00).
                insertLegacyCharge(c, UUID.randomUUID(), FEB, "100.00", "150.00", "1150.00", false);
                // An award with no benefit is still refused, and so is an award without its code.
                assertThatThrownBy(() -> insertLegacyCharge(c, UUID.randomUUID(), FEB.plusMonths(1), "0.00", "165.00",
                        "1265.00", true)).isInstanceOf(SQLException.class).hasMessageContaining("charges_charge_benefit_chk");
                try (ResultSet rs = s.executeQuery("select count(*) from charges_charge")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                assertThatThrownBy(() -> s.execute("update charges_charge set scholarship_code = 'X' "
                        + "where scholarship_award_id is null")).isInstanceOf(SQLException.class);
            }
        } finally {
            drop(database);
        }
    }

}
