package com.confia.charges.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.charges.application.AppliedBenefitView;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.scholarships.ScholarshipsFixture;
import com.confia.scholarships.application.ManageDiscounts;
import com.confia.scholarships.application.ManageScholarships;
import com.confia.scholarships.application.RequestAward;
import com.confia.scholarships.application.RequestDiscountAssignment;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.support.StaffRoleFixtures;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The scholarship and the discounts applied together by the accrual engine, against a real PostgreSQL (docs/cortes/
 * scholarships-discounts.md, T2): the acceptance example (a base of 1,000.00 with a 20 % scholarship, a 10 % stackable campaign and a
 * 15 % tax gives a net base of 720.00, a tax of 108.00 and a total of 828.00, and the ledger records 1,000.00 of revenue and 280.00 of
 * benefits), the same with a non-stackable 25 % (net base 750.00), the order of the cascade, the floor at zero, the ties, the validity
 * of a campaign with both ends, the agreement that applies only with an approved assignment, the rounding at each step, the cap per
 * period of the scholarship next to a discount, the preview that changes before the confirmation, the run that is repeated, the
 * simulator that reproduces the engine, and what the database refuses of a line that is not in force. Everything is fictional.
 */
class AccrualDiscountsIT extends ScholarshipsFixture {

    private static final LocalDate JAN_1 = LocalDate.parse("2027-01-01");
    private static final LocalDate JAN_31 = LocalDate.parse("2027-01-31");
    private static final LocalDate FEB_1 = LocalDate.parse("2027-02-01");
    private static final LocalDate FEB_2 = LocalDate.parse("2027-02-02");
    private static final LocalDate JUN_30 = LocalDate.parse("2027-06-30");
    private static final LocalDate DEC_31 = LocalDate.parse("2027-12-31");

    private record World(Env env, UUID year, AuthenticatedActor coordinator, AuthenticatedActor administrator) {

        UUID institution() {
            return env.institution();
        }

        UUID student() {
            return env.students().get(0);
        }
    }

    /** A line of a stored charge: what the charge keeps of each benefit. */
    private record Line(int position, String source, String code, BigDecimal amount, UUID assignmentId) {
    }

    private World world(String price, String taxPercent) throws SQLException {
        return worldOf(seed(1, price, taxPercent, "HNL"));
    }

    private World worldOf(Env env) throws SQLException {
        return new World(env, yearOf(env.institution()), actorOf(env.institution(), "academic_coordinator"),
                actorOf(env.institution(), "administrator"));
    }

    /** The acceptance example: a base of 1,000.00 and a tax of 15 %, with the account of the benefits of the map. */
    private World acceptanceWorld() throws SQLException {
        World w = world("1000.00", "15");
        addBenefitsAccount(w.env(), "EXPENSE");
        return w;
    }

    private UUID scholarship(World w, String code, String points, LocalDate from, LocalDate to) {
        var created = manageScholarships().create(w.administrator(), request(), code, new ManageScholarships.Draft(
                "Beca del " + points + " %", "PERCENTAGE", points, null, null, Set.of(w.env().concept())));
        UUID award = requestAward().request(w.coordinator(), request(),
                new RequestAward.Command(created.id(), w.student(), w.year(), from, to)).id();
        decideAward().approve(w.administrator(), request(), "approve-" + UUID.randomUUID(), award);
        return award;
    }

    private UUID percentDiscount(World w, String code, String kind, String points, boolean stackable, LocalDate from,
            LocalDate to) {
        return manageDiscounts().create(w.administrator(), request(), code, kind, new ManageDiscounts.Draft("Descuento " + code,
                "PERCENTAGE", points, null, from, to, stackable, Set.of(w.env().concept()))).id();
    }

    private UUID campaign(World w, String code, String points, boolean stackable) {
        return percentDiscount(w, code, "campaign", points, stackable, JAN_1, DEC_31);
    }

    private UUID fixedCampaign(World w, String code, String amount, boolean stackable) {
        return manageDiscounts().create(w.administrator(), request(), code, "campaign", new ManageDiscounts.Draft("Descuento " + code,
                "FIXED_AMOUNT", null, amount, JAN_1, DEC_31, stackable, Set.of(w.env().concept()))).id();
    }

    private UUID assignment(World w, UUID discount, LocalDate from, LocalDate to) {
        return requestAssignment().request(w.coordinator(), request(),
                new RequestDiscountAssignment.Command(discount, w.student(), from, to)).id();
    }

    private UUID approvedAssignment(World w, UUID discount) {
        UUID id = assignment(w, discount, JAN_1, DEC_31);
        decideAssignment().approve(w.administrator(), request(), "approve-" + UUID.randomUUID(), id);
        return id;
    }

    private AccrualRunView accrue(World w, String key) {
        return accrueFebruary(w.env(), key);
    }

    private AccrualPreviewResult preview(World w) {
        return manageAccrual().preview(w.env().actor(), UUID.randomUUID().toString(), w.env().plan(), FEBRUARY);
    }

    private static void assertAmount(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    /** What every charge of the institution keeps: base, benefit, net base, tax and total, in the order of the periods. */
    private List<BigDecimal[]> amounts(World w) throws SQLException {
        List<BigDecimal[]> found = new ArrayList<>();
        try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution());
                PreparedStatement s = c.prepareStatement("select base_amount, discount_amount, net_base_amount, tax_amount, "
                        + "total_amount from charges_charge where institution_id = ? order by period_start, id")) {
            s.setObject(1, w.institution());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    found.add(new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                            rs.getBigDecimal(4), rs.getBigDecimal(5)});
                }
            }
        }
        return found;
    }

    private BigDecimal[] onlyCharge(World w) throws SQLException {
        List<BigDecimal[]> all = amounts(w);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private List<Line> lines(World w) throws SQLException {
        List<Line> found = new ArrayList<>();
        try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution());
                PreparedStatement s = c.prepareStatement("select b.position, b.source, coalesce(b.scholarship_code, b.discount_code), "
                        + "b.amount, b.discount_assignment_id from charges_charge_benefit b join charges_charge c "
                        + "on c.institution_id = b.institution_id and c.id = b.charge_id where b.institution_id = ? "
                        + "order by c.period_start, c.payment_concept_id, b.position")) {
            s.setObject(1, w.institution());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    found.add(new Line(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                            rs.getObject(5, UUID.class)));
                }
            }
        }
        return found;
    }

    private static List<String> codes(List<Line> lines) {
        return lines.stream().map(Line::code).toList();
    }

    private static List<String> amountsOf(List<Line> lines) {
        return lines.stream().map(line -> line.amount().toPlainString()).toList();
    }

    /** The entries of the ledger transaction of the only charge: {@code "<account code> <side>"} to the amount. */
    private Map<String, BigDecimal> ledgerOfTheCharge(World w) throws SQLException {
        Map<String, BigDecimal> entries = new LinkedHashMap<>();
        try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution());
                PreparedStatement s = c.prepareStatement("select a.code, e.side, e.amount from ledger_entry e "
                        + "join ledger_account a on a.institution_id = e.institution_id and a.id = e.account_id "
                        + "where e.institution_id = ? and e.transaction_id = (select ledger_transaction_id from charges_charge "
                        + "where institution_id = ?) order by a.code, e.side")) {
            s.setObject(1, w.institution());
            s.setObject(2, w.institution());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    entries.put(rs.getString(1) + " " + rs.getString(2), rs.getBigDecimal(3));
                }
            }
        }
        return entries;
    }

    private SimulateCharges simulation() {
        return new SimulateCharges(transactionRunner(), new com.confia.charges.infrastructure.JooqChargePlanRepository(dsl),
                new com.confia.charges.infrastructure.JooqChargePlanItemRepository(dsl),
                new com.confia.charges.infrastructure.JooqChargeRepository(dsl),
                new com.confia.organization.infrastructure.JooqPlacementDirectory(dsl),
                new com.confia.students.infrastructure.JooqEnrollmentRoster(dsl),
                new com.confia.students.infrastructure.JooqStudentDirectory(dsl),
                new com.confia.catalog.infrastructure.JooqPricingDirectory(dsl), awardBenefits(), discountBenefits(),
                new com.confia.shared.infrastructure.JooqAuditLogWriter(dsl));
    }

    // --- The acceptance example ---

    @Test
    void aScholarshipOf20PercentAndAStackableCampaignOf10PercentGiveANetBaseOf720AndATaxOf108() throws SQLException {
        World w = acceptanceWorld();
        scholarship(w, "BECA-20", "20", FEB_1, JUN_30);
        campaign(w, "CAMP-10", "10", true);

        AccrualRunView run = accrue(w, "key-1");

        assertThat(run.status()).isEqualTo("COMPLETED");
        BigDecimal[] charge = onlyCharge(w);
        assertAmount(charge[0], "1000.00");
        assertAmount(charge[1], "280.00");
        assertAmount(charge[2], "720.00");
        assertAmount(charge[3], "108.00");
        assertAmount(charge[4], "828.00");
        // Each benefit is kept with its source and its amount, in the order it was applied.
        List<Line> lines = lines(w);
        assertThat(codes(lines)).containsExactly("BECA-20", "CAMP-10");
        assertThat(lines).extracting(Line::source).containsExactly("SCHOLARSHIP", "DISCOUNT");
        assertThat(lines).extracting(Line::position).containsExactly(1, 2);
        assertThat(amountsOf(lines)).containsExactly("200.0000", "80.0000");
        // The ledger registers 1,000.00 of revenue and 280.00 of benefits (ONE entry for the total benefit); it balances.
        Map<String, BigDecimal> ledger = ledgerOfTheCharge(w);
        assertThat(ledger).containsOnlyKeys("1105 DEBIT", "2105 CREDIT", "4105 CREDIT", "5105 DEBIT");
        assertAmount(ledger.get("4105 CREDIT"), "1000.00");
        assertAmount(ledger.get("5105 DEBIT"), "280.00");
        assertAmount(ledger.get("1105 DEBIT"), "828.00");
        assertAmount(ledger.get("2105 CREDIT"), "108.00");
        assertThat(ledger.get("1105 DEBIT").add(ledger.get("5105 DEBIT")))
                .isEqualByComparingTo(ledger.get("4105 CREDIT").add(ledger.get("2105 CREDIT")));
        assertAmount(balanceOf(w.env(), w.student()), "828.00");
    }

    @Test
    void aNonStackableDiscountOf25PercentAppliesAloneAndTheNetBaseIs750() throws SQLException {
        World w = acceptanceWorld();
        scholarship(w, "BECA-20", "20", FEB_1, JUN_30);
        campaign(w, "CAMP-25", "25", false);

        accrue(w, "key-1");

        BigDecimal[] charge = onlyCharge(w);
        assertAmount(charge[1], "250.00");
        assertAmount(charge[2], "750.00");
        assertAmount(charge[3], "112.50");
        assertAmount(charge[4], "862.50");
        assertThat(codes(lines(w))).containsExactly("CAMP-25");
        // The scholarship does not apply, so the charge names none; the award was not consumed.
        assertThat(scalar(w.institution(), "select count(*) from charges_charge where institution_id = ? "
                + "and scholarship_award_id is not null", w.institution())).isZero();
        assertAmount(ledgerOfTheCharge(w).get("5105 DEBIT"), "250.00");
    }

    @Test
    void thePreviewAndTheSimulatorShowTheSameAsTheEngineAndTheSimulatorWritesOnlyItsAuditEntry() throws SQLException {
        World w = acceptanceWorld();
        UUID award = scholarship(w, "BECA-20", "20", FEB_1, JUN_30);
        UUID discount = campaign(w, "CAMP-10", "10", true);

        AccrualPreviewResult preview = preview(w);
        SimulateCharges.Simulation simulation = simulation().simulate(w.env().actor(), UUID.randomUUID().toString(),
                w.env().plan(), w.student(), FEB_1);

        assertThat(preview.blockers()).isEmpty();
        assertThat(preview.items()).singleElement().satisfies(item -> {
            assertAmount(item.base().amount(), "1000.00");
            assertAmount(item.benefit().amount(), "280.00");
            assertAmount(item.netBase().amount(), "720.00");
            assertAmount(item.tax().amount(), "108.00");
            assertAmount(item.total().amount(), "828.00");
            assertThat(item.awardId()).isEqualTo(award);
            assertThat(item.benefits()).extracting(AppliedBenefitView::code).containsExactly("BECA-20", "CAMP-10");
            assertThat(item.benefits().get(1).discountId()).isEqualTo(discount);
            assertThat(item.benefits().get(1).assignmentId()).isNull();
        });
        assertThat(preview.totals()).singleElement().satisfies(total -> assertAmount(total.benefit().amount(), "280.00"));
        assertThat(simulation.lines()).singleElement().satisfies(line -> {
            assertAmount(line.benefit().amount(), "280.00");
            assertAmount(line.netBase().amount(), "720.00");
            assertAmount(line.tax().amount(), "108.00");
            assertAmount(line.total().amount(), "828.00");
            assertThat(line.benefits()).extracting(AppliedBenefitView::code).containsExactly("BECA-20", "CAMP-10");
            assertThat(line.benefits()).extracting(benefit -> benefit.amount().toPlainString())
                    .containsExactly("200.0000", "80.0000");
        });
        assertThat(amounts(w)).isEmpty();

        accrue(w, "key-1");
        // What the engine wrote is exactly what both showed.
        assertAmount(onlyCharge(w)[2], "720.00");
        assertThat(amountsOf(lines(w))).isEqualTo(simulation.lines().get(0).benefits().stream()
                .map(benefit -> benefit.amount().toPlainString()).toList());
    }

    @Test
    void theSimulatorShowsTheNonStackableCaseToo() throws SQLException {
        World w = acceptanceWorld();
        scholarship(w, "BECA-20", "20", FEB_1, JUN_30);
        campaign(w, "CAMP-25", "25", false);

        SimulateCharges.Simulation simulation = simulation().simulate(w.env().actor(), UUID.randomUUID().toString(),
                w.env().plan(), w.student(), FEB_1);

        assertThat(simulation.lines()).singleElement().satisfies(line -> {
            assertAmount(line.netBase().amount(), "750.00");
            assertThat(line.benefits()).extracting(AppliedBenefitView::code).containsExactly("CAMP-25");
            assertThat(line.scholarshipAwardId()).isNull();
        });
    }

    // --- The order of the cascade, the floor, the ties ---

    @Test
    void thePercentageDiscountsGoBeforeTheFixedAmountOnesWhateverTheirCodes() throws SQLException {
        World w = acceptanceWorld();
        // By code the fixed amount would come first: A-FIJO before Z-PCT. The percentage goes first all the same.
        fixedCampaign(w, "A-FIJO", "50.00", true);
        campaign(w, "Z-PCT", "10", true);

        accrue(w, "key-1");

        List<Line> lines = lines(w);
        assertThat(codes(lines)).containsExactly("Z-PCT", "A-FIJO");
        assertThat(amountsOf(lines)).containsExactly("100.0000", "50.0000");
        assertAmount(onlyCharge(w)[2], "850.00");
    }

    @Test
    void twoPercentageDiscountsApplyByCodeEachOnWhatIsLeft() throws SQLException {
        World w = acceptanceWorld();
        campaign(w, "B-20", "20", true);
        campaign(w, "A-10", "10", true);

        accrue(w, "key-1");

        // 10 % of 1,000.00 is 100.00, then 20 % of 900.00 is 180.00.
        assertThat(amountsOf(lines(w))).containsExactly("100.0000", "180.0000");
        assertThat(codes(lines(w))).containsExactly("A-10", "B-20");
        assertAmount(onlyCharge(w)[2], "720.00");
    }

    @Test
    void benefitsThatWouldAddUpToMoreThanTheBaseLeaveItAtZeroAndThePostingHasNoReceivable() throws SQLException {
        World w = acceptanceWorld();
        scholarship(w, "BECA-60", "60", FEB_1, JUN_30);
        fixedCampaign(w, "FIJO-A", "300.00", true);
        fixedCampaign(w, "FIJO-B", "300.00", true);

        assertThat(accrue(w, "key-1").status()).isEqualTo("COMPLETED");

        // 600.00, then 300.00 of the 400.00 left, then 100.00 of the 100.00 left: the base is gone and never below zero.
        BigDecimal[] charge = onlyCharge(w);
        assertAmount(charge[1], "1000.00");
        assertAmount(charge[2], "0.00");
        assertAmount(charge[3], "0.00");
        assertAmount(charge[4], "0.00");
        assertThat(amountsOf(lines(w))).containsExactly("600.0000", "300.0000", "100.0000");
        assertThat(ledgerOfTheCharge(w)).containsOnlyKeys("4105 CREDIT", "5105 DEBIT");
        assertAmount(ledgerOfTheCharge(w).get("5105 DEBIT"), "1000.00");
    }

    @Test
    void aSecondDiscountThatFindsNothingLeftLeavesNoLine() throws SQLException {
        World w = acceptanceWorld();
        fixedCampaign(w, "A-TODO", "1000.00", true);
        campaign(w, "B-DIEZ", "10", true);

        accrue(w, "key-1");

        // The percentage goes first (B-DIEZ, 100.00) and the fixed amount takes what is left (900.00): two lines, nothing below zero.
        assertThat(codes(lines(w))).containsExactly("B-DIEZ", "A-TODO");
        assertThat(amountsOf(lines(w))).containsExactly("100.0000", "900.0000");
        assertAmount(onlyCharge(w)[2], "0.00");
    }

    @Test
    void aTieBetweenTheScholarshipAndANonStackableDiscountGoesToTheScholarship() throws SQLException {
        World w = acceptanceWorld();
        scholarship(w, "ZZZ-BECA", "20", FEB_1, JUN_30);
        campaign(w, "AAA-DESC", "20", false);

        accrue(w, "key-1");

        assertThat(codes(lines(w))).containsExactly("ZZZ-BECA");
        assertAmount(onlyCharge(w)[2], "800.00");
    }

    @Test
    void aTieBetweenNonStackableDiscountsGoesToTheLowestCode() throws SQLException {
        World w = acceptanceWorld();
        campaign(w, "B-DESC", "15", false);
        campaign(w, "A-DESC", "15", false);

        accrue(w, "key-1");

        assertThat(codes(lines(w))).containsExactly("A-DESC");
        assertAmount(onlyCharge(w)[2], "850.00");
    }

    @Test
    void aStackableDiscountDoesNotAddToANonStackableOneThatWinsEvenAfterThePreviewShowedBoth() throws SQLException {
        World w = acceptanceWorld();
        campaign(w, "A-STACK", "10", true);
        campaign(w, "B-ALONE", "30", false);

        accrue(w, "key-1");

        assertThat(codes(lines(w))).containsExactly("B-ALONE");
        assertAmount(onlyCharge(w)[1], "300.00");
    }

    @Test
    void theRoundingIsHalfUpAtEachStepOfTheCascadeAndTheTaxOfTheNetBaseIsRoundedOnce() throws SQLException {
        // 10.04 less 12.5 % (1.255 -> 1.26) leaves 8.78; 12.5 % of it (1.0975 -> 1.10) leaves 7.68; 10 % of 7.68 is 0.768 -> 0.77.
        World w = world("10.04", "10");
        addBenefitsAccount(w.env(), "EXPENSE");
        scholarship(w, "MEDIO", "12.50", FEB_1, JUN_30);
        campaign(w, "OTRO-MEDIO", "12.50", true);

        accrue(w, "key-1");

        assertThat(amountsOf(lines(w))).containsExactly("1.2600", "1.1000");
        BigDecimal[] charge = onlyCharge(w);
        assertAmount(charge[1], "2.36");
        assertAmount(charge[2], "7.68");
        assertAmount(charge[3], "0.77");
        assertAmount(charge[4], "8.45");
    }

    // --- The validity of a campaign and what it covers ---

    @Test
    void aCampaignThatStartsOnTheDayOfTheChargeAppliesAndOneThatStartsTheNextDayDoesNot() throws SQLException {
        World w = acceptanceWorld();
        percentDiscount(w, "EMPIEZA-HOY", "campaign", "10", true, FEB_1, DEC_31);
        percentDiscount(w, "EMPIEZA-MANANA", "campaign", "20", true, FEB_2, DEC_31);

        accrue(w, "key-1");

        assertThat(codes(lines(w))).containsExactly("EMPIEZA-HOY");
    }

    @Test
    void aCampaignThatEndsOnTheDayOfTheChargeAppliesAndOneThatEndedTheDayBeforeDoesNot() throws SQLException {
        World w = acceptanceWorld();
        percentDiscount(w, "TERMINA-HOY", "campaign", "10", true, JAN_1, FEB_1);
        percentDiscount(w, "TERMINO-AYER", "campaign", "20", true, JAN_1, JAN_31);

        accrue(w, "key-1");

        assertThat(codes(lines(w))).containsExactly("TERMINA-HOY");
    }

    @Test
    void anInactiveDiscountDoesNotApply() throws SQLException {
        World w = acceptanceWorld();
        UUID discount = campaign(w, "CAMP-10", "10", true);
        manageDiscounts().deactivate(w.administrator(), request(), discount);

        accrue(w, "key-1");

        assertThat(lines(w)).isEmpty();
        assertAmount(onlyCharge(w)[1], "0.00");
    }

    @Test
    void aDiscountThatDoesNotCoverTheConceptOfTheChargeAppliesOnlyToTheOneItCovers() throws SQLException {
        World w = worldOf(seed(1, "1000.00", "15", "HNL", Concepts.TWO_IN_ONE_PLAN));
        addBenefitsAccount(w.env(), "EXPENSE");
        UUID second = secondConcept(w.env());
        manageDiscounts().create(w.administrator(), request(), "SOLO-SEGUNDO", "campaign", new ManageDiscounts.Draft("Solo el segundo",
                "PERCENTAGE", "10", null, JAN_1, DEC_31, true, Set.of(second)));

        accrue(w, "key-1");

        assertThat(amounts(w)).hasSize(2);
        assertThat(lines(w)).hasSize(1);
        assertThat(scalar(w.institution(), "select count(*) from charges_charge c where c.institution_id = ? and "
                + "c.payment_concept_id = ? and c.discount_amount > 0", w.institution(), second)).isEqualTo(1);
        assertThat(scalar(w.institution(), "select count(*) from charges_charge c where c.institution_id = ? and "
                + "c.payment_concept_id = ? and c.discount_amount > 0", w.institution(), w.env().concept())).isZero();
    }

    // --- The agreement discount applies only through an approved assignment ---

    @Test
    void anAgreementWithoutAssignmentPendingRejectedOrRevokedDoesNotApplyAndAnApprovedOneDoes() throws SQLException {
        World w = acceptanceWorld();
        UUID agreement = percentDiscount(w, "CONV-10", "agreement", "10", true, JAN_1, DEC_31);

        // Nothing: no assignment.
        assertThat(preview(w).items()).singleElement().satisfies(item -> assertAmount(item.benefit().amount(), "0.00"));
        // Pending.
        UUID pending = assignment(w, agreement, JAN_1, DEC_31);
        assertThat(preview(w).items()).singleElement().satisfies(item -> assertAmount(item.benefit().amount(), "0.00"));
        // Rejected.
        decideAssignment().reject(w.administrator(), request(), "reject-1", pending, REASON);
        assertThat(preview(w).items()).singleElement().satisfies(item -> assertAmount(item.benefit().amount(), "0.00"));
        // Approved and then revoked.
        UUID second = assignment(w, agreement, JAN_1, DEC_31);
        decideAssignment().approve(w.administrator(), request(), "approve-2", second);
        decideAssignment().revoke(w.administrator(), request(), "revoke-2", second, REASON);
        assertThat(preview(w).items()).singleElement().satisfies(item -> assertAmount(item.benefit().amount(), "0.00"));
        // Approved and in force: it applies, with the assignment that made it apply.
        UUID third = assignment(w, agreement, JAN_1, DEC_31);
        decideAssignment().approve(w.administrator(), request(), "approve-3", third);

        accrue(w, "key-1");

        List<Line> lines = lines(w);
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.code()).isEqualTo("CONV-10");
            assertThat(line.assignmentId()).isEqualTo(third);
            assertAmount(line.amount(), "100.00");
        });
    }

    @Test
    void anAssignmentThatDoesNotCoverTheDayOfTheChargeDoesNotApplyTheAgreement() throws SQLException {
        World w = acceptanceWorld();
        UUID agreement = percentDiscount(w, "CONV-10", "agreement", "10", true, JAN_1, DEC_31);
        UUID later = assignment(w, agreement, FEB_2, DEC_31);
        decideAssignment().approve(w.administrator(), request(), "approve-1", later);

        accrue(w, "key-1");

        assertThat(lines(w)).isEmpty();
    }

    @Test
    void anAgreementAndACampaignAndTheScholarshipStackInOrder() throws SQLException {
        World w = acceptanceWorld();
        scholarship(w, "BECA-20", "20", FEB_1, JUN_30);
        campaign(w, "CAMP-10", "10", true);
        UUID agreement = percentDiscount(w, "CONV-05", "agreement", "5", true, JAN_1, DEC_31);
        UUID assignment = approvedAssignment(w, agreement);

        accrue(w, "key-1");

        // 1,000.00 -> 800.00 -> 720.00 -> 684.00: the scholarship, then the percentage discounts by code.
        assertThat(codes(lines(w))).containsExactly("BECA-20", "CAMP-10", "CONV-05");
        assertThat(amountsOf(lines(w))).containsExactly("200.0000", "80.0000", "36.0000");
        assertThat(lines(w).get(2).assignmentId()).isEqualTo(assignment);
        assertAmount(onlyCharge(w)[2], "684.00");
    }

    // --- The cap of the scholarship next to the discounts ---

    @Test
    void theCapOfTheScholarshipLimitsItAndTheDiscountIsComputedOnWhatIsLeft() throws SQLException {
        World w = world("1100.00", "15");
        addBenefitsAccount(w.env(), "EXPENSE");
        var created = manageScholarships().create(w.administrator(), request(), "TOPE", new ManageScholarships.Draft(
                "Beca con tope", "PERCENTAGE", "50", null, "300.00", Set.of(w.env().concept())));
        UUID award = requestAward().request(w.coordinator(), request(),
                new RequestAward.Command(created.id(), w.student(), w.year(), FEB_1, JUN_30)).id();
        decideAward().approve(w.administrator(), request(), "approve-1", award);
        campaign(w, "CAMP-10", "10", true);

        accrue(w, "key-1");

        // The scholarship gives the 300.00 of its cap (not 550.00); then 10 % of the 800.00 that are left is 80.00.
        assertThat(amountsOf(lines(w))).containsExactly("300.0000", "80.0000");
        BigDecimal[] charge = onlyCharge(w);
        assertAmount(charge[1], "380.00");
        assertAmount(charge[2], "720.00");
        assertAmount(charge[3], "108.00");
    }

    @Test
    void theCapOfTheScholarshipIsSharedByTheChargesOfThePeriodAndCountsOnlyWhatTheScholarshipGave() throws SQLException {
        World w = worldOf(seed(1, "1100.00", "15", "HNL", Concepts.TWO_IN_ONE_PLAN));
        addBenefitsAccount(w.env(), "EXPENSE");
        var created = manageScholarships().create(w.administrator(), request(), "TOPE", new ManageScholarships.Draft(
                "Beca con tope", "PERCENTAGE", "50", null, "600.00", Set.of(w.env().concept(), secondConcept(w.env()))));
        UUID award = requestAward().request(w.coordinator(), request(),
                new RequestAward.Command(created.id(), w.student(), w.year(), FEB_1, JUN_30)).id();
        decideAward().approve(w.administrator(), request(), "approve-1", award);
        manageDiscounts().create(w.administrator(), request(), "CAMP-10", "campaign", new ManageDiscounts.Draft("Campana",
                "PERCENTAGE", "10", null, JAN_1, DEC_31, true, Set.of(w.env().concept(), secondConcept(w.env()))));

        accrue(w, "key-1");

        // The first charge gives 550.00 of scholarship and 55.00 of discount; the second one only the 50.00 that is left of the cap
        // (the 55.00 of discount of the first one is not the scholarship's) and 10 % of 1,050.00, which is 105.00.
        List<BigDecimal> scholarship = new ArrayList<>();
        List<BigDecimal> discount = new ArrayList<>();
        for (Line line : lines(w)) {
            (line.source().equals("SCHOLARSHIP") ? scholarship : discount).add(line.amount());
        }
        assertThat(scholarship).extracting(BigDecimal::toPlainString).containsExactlyInAnyOrder("550.0000", "50.0000");
        assertThat(discount).extracting(BigDecimal::toPlainString).containsExactlyInAnyOrder("55.0000", "105.0000");
    }

    // --- Nothing is generated twice, and nothing when the benefits changed since the preview ---

    @Test
    void generatingThePeriodAgainCreatesNothingNewAndAddsNoLine() throws SQLException {
        World w = acceptanceWorld();
        scholarship(w, "BECA-20", "20", FEB_1, JUN_30);
        campaign(w, "CAMP-10", "10", true);
        accrue(w, "key-1");

        AccrualRunView again = accrue(w, "key-2");

        assertThat(again.chargesCreated()).isZero();
        assertThat(again.chargesExisting()).isEqualTo(1);
        assertThat(lines(w)).hasSize(2);
        assertThat(amounts(w)).hasSize(1);
    }

    @Test
    void changingTheDiscountAfterTheChargeWasIssuedChangesNothingOfIt() throws SQLException {
        World w = acceptanceWorld();
        UUID discount = campaign(w, "CAMP-10", "10", true);
        accrue(w, "key-1");

        manageDiscounts().deactivate(w.administrator(), request(), discount);
        AccrualRunView again = accrue(w, "key-2");

        assertThat(again.chargesCreated()).isZero();
        assertAmount(onlyCharge(w)[1], "100.00");
        assertThat(amountsOf(lines(w))).containsExactly("100.0000");
    }

    @Test
    void aDiscountThatChangesBetweenThePreviewAndTheConfirmationRejectsTheRunWithNothingWritten() throws SQLException {
        World w = acceptanceWorld();
        UUID discount = campaign(w, "CAMP-10", "10", true);
        AccrualPreviewResult preview = preview(w);
        manageDiscounts().deactivate(w.administrator(), request(), discount);

        AccrualRunView run = manageAccrual().request(w.env().actor(), UUID.randomUUID().toString(), "key-1", w.env().plan(),
                FEBRUARY, preview.previewDigest());
        executeAccrualRun().execute(w.env().system(), run.id());

        AccrualRunView after = manageAccrual().get(w.env().actor(), UUID.randomUUID().toString(), run.id(), 0, 200).run();
        assertThat(after.status()).isEqualTo("REJECTED");
        assertThat(after.failureCode()).isEqualTo("preview-outdated");
        assertThat(amounts(w)).isEmpty();
        assertThat(lines(w)).isEmpty();
    }

    @Test
    void anAssignmentThatIsRevokedBetweenThePreviewAndTheConfirmationRejectsTheRunWithNothingWritten() throws SQLException {
        World w = acceptanceWorld();
        UUID agreement = percentDiscount(w, "CONV-10", "agreement", "10", true, JAN_1, DEC_31);
        UUID assignment = approvedAssignment(w, agreement);
        AccrualPreviewResult preview = preview(w);
        assertAmount(preview.items().get(0).benefit().amount(), "100.00");
        decideAssignment().revoke(w.administrator(), request(), "revoke-1", assignment, REASON);

        AccrualRunView run = manageAccrual().request(w.env().actor(), UUID.randomUUID().toString(), "key-1", w.env().plan(),
                FEBRUARY, preview.previewDigest());
        executeAccrualRun().execute(w.env().system(), run.id());

        AccrualRunView after = manageAccrual().get(w.env().actor(), UUID.randomUUID().toString(), run.id(), 0, 200).run();
        assertThat(after.status()).isEqualTo("REJECTED");
        assertThat(amounts(w)).isEmpty();
    }

    @Test
    void withDiscountsInForceThePeriodNeedsTheAccountOfTheBenefits() throws SQLException {
        World w = world("1000.00", "15");
        campaign(w, "CAMP-10", "10", true);

        AccrualPreviewResult preview = preview(w);

        assertThat(preview.blockerCodes()).contains("posting-accounts-incomplete");
    }

    // --- The student's charges, the integrity check and the in-use hook ---

    @Test
    void theStudentChargesListEveryBenefitAndTheNightlyCheckFindsTheChargeSound() throws SQLException {
        World w = acceptanceWorld();
        UUID award = scholarship(w, "BECA-20", "20", FEB_1, JUN_30);
        campaign(w, "CAMP-10", "10", true);
        accrue(w, "key-1");

        var page = listStudentCharges().list(w.env().actor(), UUID.randomUUID().toString(), w.student(),
                new ChargeFilter(null, null, null, false), new PageRequest(0, 50, "periodStart", true));

        assertThat(page.items()).singleElement().satisfies(view -> {
            var row = view.charge();
            assertAmount(row.benefit().amount(), "280.00");
            assertAmount(row.netBase().amount(), "720.00");
            assertThat(row.scholarshipAwardId()).isEqualTo(award);
            assertThat(row.benefits()).extracting(AppliedBenefitView::code).containsExactly("BECA-20", "CAMP-10");
            assertAmount(view.outstanding().amount(), "828.00");
        });
        integrityCheck().runFor(new InstitutionId(w.institution()), new com.confia.shared.jobs.RecurringRun(
                com.confia.charges.application.RunChargesIntegrityCheck.TASK_NAME, UUID.randomUUID(), CLOCK.instant()));
        assertThat(viewIntegrityRuns().latest(w.env().actor(), UUID.randomUUID().toString()).run().status()).isEqualTo("OK");
    }

    @Test
    void aDiscountWithAChargeIssuedWithItIsInUseItsBenefitNeverChangesAndItsScopeOnlyNarrows() throws SQLException {
        World w = acceptanceWorld();
        UUID discount = campaign(w, "CAMP-10", "10", true);
        assertThat(manageDiscounts().get(w.administrator(), request(), discount).inUse()).isFalse();
        accrue(w, "key-1");

        assertThat(manageDiscounts().get(w.administrator(), request(), discount).inUse()).isTrue();
        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), discount, 0,
                new ManageDiscounts.Draft("Campana", "PERCENTAGE", "20", null, JAN_1, DEC_31, true, Set.of(w.env().concept()))))
                .isInstanceOf(com.confia.scholarships.domain.DiscountBenefitLockedException.class);
        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), discount, 0,
                new ManageDiscounts.Draft("Campana", "PERCENTAGE", "10", null, JAN_1.minusDays(1), DEC_31, true,
                        Set.of(w.env().concept()))))
                .isInstanceOf(com.confia.scholarships.domain.DiscountScopeLockedException.class);
        // Narrowing the validity and deactivating are allowed, and the charge already issued does not change.
        var narrowed = manageDiscounts().update(w.administrator(), request(), discount, 0,
                new ManageDiscounts.Draft("Campana", "PERCENTAGE", "10", null, FEB_1, JUN_30, true, Set.of(w.env().concept())));
        assertThat(narrowed.validFrom()).isEqualTo(FEB_1);
        assertAmount(onlyCharge(w)[1], "100.00");
    }

    @Test
    void theDatabaseHoldsTheBenefitAndTheScopeOfADiscountWithChargesEvenAgainstADirectWrite() throws SQLException {
        World w = acceptanceWorld();
        UUID discount = campaign(w, "CAMP-10", "10", true);
        accrue(w, "key-1");

        assertThatThrownBy(() -> {
            try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution())) {
                exec(c, "update scholarships_discount set percent = 20 where institution_id = ? and id = ?", w.institution(),
                        discount);
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("benefit of a discount in use");
        assertThatThrownBy(() -> {
            try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution())) {
                exec(c, "insert into scholarships_discount_concept (institution_id, discount_id, payment_concept_id) "
                        + "values (?, ?, ?)", w.institution(), discount, secondConcept(w.env()));
            }
        }).isInstanceOf(SQLException.class);
    }

    // --- What the database refuses of a line that is not in force ---

    /** An engine that offers this discount whatever the database says, to prove the database refuses what is not in force. */
    private DiscountBenefits offering(UUID discount, String code, UUID assignment, UnaryOperator<Money> benefit) {
        return new DiscountBenefits() {
            @Override
            public List<Applicable> inForce(InstitutionId institution, UUID student, UUID concept, LocalDate on,
                    CurrencyCode currency, boolean lock) {
                return List.of(new Applicable(discount, code, assignment, true, true, benefit));
            }

            @Override
            public boolean anyApplicable(InstitutionId institution, Collection<UUID> students, Collection<UUID> concepts,
                    LocalDate on) {
                return true;
            }
        };
    }

    private void assertTheDatabaseRefuses(World w, DiscountBenefits engine) throws SQLException {
        AccrualRunView run = manageAccrual().request(w.env().actor(), UUID.randomUUID().toString(), "key-" + UUID.randomUUID(),
                w.env().plan(), FEBRUARY, null);
        executeAccrualRun(awardBenefits(), engine).execute(w.env().system(), run.id());
        AccrualRunView after = manageAccrual().get(w.env().actor(), UUID.randomUUID().toString(), run.id(), 0, 200).run();
        assertThat(after.chargesCreated()).isZero();
        assertThat(after.chargesFailed()).isEqualTo(1);
        assertThat(amounts(w)).isEmpty();
        assertThat(lines(w)).isEmpty();
    }

    private static UnaryOperator<Money> tenPercent() {
        return base -> base.percentageToMinorUnit(com.confia.kernel.Percentage.of("10"), java.math.RoundingMode.HALF_UP);
    }

    @Test
    void theDatabaseRefusesALineOfAnInactiveDiscount() throws SQLException {
        World w = acceptanceWorld();
        UUID discount = campaign(w, "CAMP-10", "10", true);
        manageDiscounts().deactivate(w.administrator(), request(), discount);

        assertTheDatabaseRefuses(w, offering(discount, "CAMP-10", null, tenPercent()));
    }

    @Test
    void theDatabaseRefusesALineOfADiscountThatDoesNotCoverTheConceptOrIsNotInForceAtThePricingDate() throws SQLException {
        World w = worldOf(seed(1, "1000.00", "15", "HNL", Concepts.TWO_IN_TWO_PLANS));
        addBenefitsAccount(w.env(), "EXPENSE");
        UUID elsewhere = manageDiscounts().create(w.administrator(), request(), "OTRO-CONCEPTO", "campaign",
                new ManageDiscounts.Draft("Otro concepto", "PERCENTAGE", "10", null, JAN_1, DEC_31, true,
                        Set.of(secondConcept(w.env())))).id();
        UUID later = percentDiscount(w, "TARDE", "campaign", "10", true, FEB_2, DEC_31);

        assertTheDatabaseRefuses(w, offering(elsewhere, "OTRO-CONCEPTO", null, tenPercent()));
        assertTheDatabaseRefuses(w, offering(later, "TARDE", null, tenPercent()));
    }

    @Test
    void theDatabaseRefusesALineWithAnotherCodeThanTheOneOfTheDiscount() throws SQLException {
        World w = acceptanceWorld();
        UUID discount = campaign(w, "CAMP-10", "10", true);

        assertTheDatabaseRefuses(w, offering(discount, "OTRO-CODIGO", null, tenPercent()));
    }

    @Test
    void theDatabaseRefusesAnAgreementLineWithoutAnApprovedAssignmentOfTheStudent() throws SQLException {
        World w = acceptanceWorld();
        UUID agreement = percentDiscount(w, "CONV-10", "agreement", "10", true, JAN_1, DEC_31);
        UUID pending = assignment(w, agreement, JAN_1, DEC_31);
        UUID rejected = assignment(w, agreement, JAN_1, DEC_31);
        decideAssignment().reject(w.administrator(), request(), "reject-1", rejected, REASON);

        assertTheDatabaseRefuses(w, offering(agreement, "CONV-10", null, tenPercent()));
        assertTheDatabaseRefuses(w, offering(agreement, "CONV-10", pending, tenPercent()));
        assertTheDatabaseRefuses(w, offering(agreement, "CONV-10", rejected, tenPercent()));
    }

    @Test
    void theDatabaseRefusesACampaignLineWithAnAssignment() throws SQLException {
        World w = acceptanceWorld();
        UUID campaign = campaign(w, "CAMP-10", "10", true);
        UUID agreement = percentDiscount(w, "CONV-10", "agreement", "10", true, JAN_1, DEC_31);
        UUID assignment = approvedAssignment(w, agreement);

        assertTheDatabaseRefuses(w, offering(campaign, "CAMP-10", assignment, tenPercent()));
    }

    @Test
    void theDatabaseRefusesALineAboveTheBenefitOfItsDiscountOnTheBase() throws SQLException {
        World w = acceptanceWorld();
        UUID discount = campaign(w, "CAMP-10", "10", true);

        // 20 % of the base where the discount is of 10 %.
        assertTheDatabaseRefuses(w, offering(discount, "CAMP-10", null,
                base -> base.percentageToMinorUnit(com.confia.kernel.Percentage.of("20"), java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void theDatabaseRefusesTwoLinesOfTheSameDiscountAndLinesThatAddUpToMoreThanTheBenefitOfTheCharge() throws SQLException {
        World w = acceptanceWorld();
        UUID discount = campaign(w, "CAMP-10", "10", true);
        accrue(w, "key-1");
        UUID chargeId = chargesByStudent(w.env()).get(w.student());

        // The charge has its lines already: one more would make them add up to more than its benefit.
        assertThatThrownBy(() -> {
            try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution())) {
                exec(c, "insert into charges_charge_benefit (institution_id, charge_id, position, source, discount_id, "
                        + "discount_code, amount, currency) values (?, ?, 2, 'DISCOUNT', ?, 'CAMP-10', 10.00, 'HNL')",
                        w.institution(), chargeId, discount);
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    void aLineIsNeverUpdatedNorDeleted() throws SQLException {
        World w = acceptanceWorld();
        campaign(w, "CAMP-10", "10", true);
        accrue(w, "key-1");

        assertThatThrownBy(() -> {
            try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution())) {
                exec(c, "update charges_charge_benefit set amount = 1.00 where institution_id = ?", w.institution());
            }
        }).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> {
            try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution())) {
                exec(c, "delete from charges_charge_benefit where institution_id = ?", w.institution());
            }
        }).isInstanceOf(SQLException.class);
        assertThat(lines(w)).hasSize(1);
    }

    @Test
    void theLinesOfOneInstitutionAreInvisibleToAnother() throws SQLException {
        World w = acceptanceWorld();
        World other = acceptanceWorld();
        campaign(w, "CAMP-10", "10", true);
        accrue(w, "key-1");

        assertThat(lines(w)).hasSize(1);
        assertThat(lines(other)).isEmpty();
        assertThat(scalar(other.institution(), "select count(*) from charges_charge_benefit")).isZero();
    }
}
