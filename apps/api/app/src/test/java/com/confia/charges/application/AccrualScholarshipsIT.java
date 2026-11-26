package com.confia.charges.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.scholarships.ScholarshipsFixture;
import com.confia.scholarships.application.ManageScholarships;
import com.confia.scholarships.application.RequestAward;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.jobs.RecurringRun;
import com.confia.kernel.InstitutionId;
import com.confia.support.StaffRoleFixtures;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The approved scholarships applied by the accrual engine, against a real PostgreSQL (docs/cortes/scholarships-awards.md, T2): the
 * acceptance example (a base of 1,100.00 with a 25 % scholarship and a 15 % tax gives a net base of 825.00 and a tax of 123.75, and the
 * ledger records 1,100.00 of revenue and 275.00 of scholarships), the preview and the simulator that show the same, the edge cases of
 * the rule (a hundred percent, a fixed amount above the base, the cap, the midpoint of the rounding, the validity with both ends, an
 * award that is not approved, two awards on the same charge, a concept that is not covered, another currency), the map without the
 * account of the benefits, the digest of the preview, the charges that are never changed and the nightly integrity check. Everything
 * is fictional.
 */
class AccrualScholarshipsIT extends ScholarshipsFixture {

    private static final LocalDate JAN_1 = LocalDate.parse("2027-01-01");
    private static final LocalDate FEB_1 = LocalDate.parse("2027-02-01");
    private static final LocalDate JUN_30 = LocalDate.parse("2027-06-30");

    private record World(Env env, UUID year, AuthenticatedActor coordinator, AuthenticatedActor administrator) {

        UUID institution() {
            return env.institution();
        }

        UUID student() {
            return env.students().get(0);
        }
    }

    /** What a stored charge says about the benefit: every amount as the database holds it. */
    private record Stored(BigDecimal base, BigDecimal discount, BigDecimal netBase, BigDecimal tax, BigDecimal total,
            UUID awardId, String scholarshipCode) {
    }

    private World world(String price, String taxPercent, String currency) throws SQLException {
        Env env = seed(1, price, taxPercent, currency);
        return new World(env, yearOf(env.institution()), actorOf(env.institution(), "academic_coordinator"),
                actorOf(env.institution(), "administrator"));
    }

    private World world() throws SQLException {
        return world("1100.00", "15", "HNL");
    }

    private ManageScholarships.Draft percent(World w, String points, String cap) {
        return new ManageScholarships.Draft("Beca del " + points + " %", "PERCENTAGE", points, null, cap,
                Set.of(w.env().concept()));
    }

    private ManageScholarships.Draft fixed(World w, String amount) {
        return new ManageScholarships.Draft("Beca fija", "FIXED_AMOUNT", null, amount, null, Set.of(w.env().concept()));
    }

    /** A scholarship defined, an award requested for the first student and approved by another person: the id of the award. */
    private UUID approvedAward(World w, String code, ManageScholarships.Draft draft, LocalDate from, LocalDate to) {
        UUID id = requestedAward(w, code, draft, from, to);
        decideAward().approve(w.administrator(), request(), "approve-" + UUID.randomUUID(), id);
        return id;
    }

    private UUID requestedAward(World w, String code, ManageScholarships.Draft draft, LocalDate from, LocalDate to) {
        var scholarship = manageScholarships().create(w.administrator(), request(), code, draft);
        return requestAward().request(w.coordinator(), request(),
                new RequestAward.Command(scholarship.id(), w.student(), w.year(), from, to)).id();
    }

    private Stored charge(World w) throws SQLException {
        List<Stored> all = charges(w);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private List<Stored> charges(World w) throws SQLException {
        List<Stored> found = new ArrayList<>();
        try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution());
                PreparedStatement s = c.prepareStatement("select base_amount, discount_amount, net_base_amount, tax_amount, "
                        + "total_amount, scholarship_award_id, scholarship_code from charges_charge where institution_id = ? "
                        + "order by period_start")) {
            s.setObject(1, w.institution());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    found.add(new Stored(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                            rs.getBigDecimal(5), rs.getObject(6, UUID.class), rs.getString(7)));
                }
            }
        }
        return found;
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

    private static void assertAmount(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    private AccrualRunView accrue(World w, String key) {
        return accrueFebruary(w.env(), key);
    }

    private AccrualPreviewResult preview(World w) {
        return manageAccrual().preview(w.env().actor(), UUID.randomUUID().toString(), w.env().plan(), FEBRUARY);
    }

    private void execute(UUID runId, World w) {
        executeAccrualRun().execute(w.env().system(), runId);
    }

    // --- The acceptance example ---

    @Test
    void aQuarterScholarshipOnABaseOf1100WithATaxOf15GivesANetBaseOf825AndATaxOf12375() throws SQLException {
        World w = world();
        UUID benefits = addBenefitsAccount(w.env(), "EXPENSE");
        UUID award = approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);

        AccrualRunView run = accrue(w, "key-1");

        assertThat(run.status()).isEqualTo("COMPLETED");
        Stored stored = charge(w);
        assertAmount(stored.base(), "1100.00");
        assertAmount(stored.discount(), "275.00");
        assertAmount(stored.netBase(), "825.00");
        assertAmount(stored.tax(), "123.75");
        assertAmount(stored.total(), "948.75");
        assertThat(stored.awardId()).isEqualTo(award);
        assertThat(stored.scholarshipCode()).isEqualTo("BECA-25");
        // The ledger records 1,100.00 of revenue and 275.00 of scholarships; the transaction balances.
        Map<String, BigDecimal> ledger = ledgerOfTheCharge(w);
        assertThat(ledger).containsOnlyKeys("1105 DEBIT", "2105 CREDIT", "4105 CREDIT", "5105 DEBIT");
        assertAmount(ledger.get("4105 CREDIT"), "1100.00");
        assertAmount(ledger.get("5105 DEBIT"), "275.00");
        assertAmount(ledger.get("1105 DEBIT"), "948.75");
        assertAmount(ledger.get("2105 CREDIT"), "123.75");
        BigDecimal debits = ledger.get("1105 DEBIT").add(ledger.get("5105 DEBIT"));
        BigDecimal credits = ledger.get("4105 CREDIT").add(ledger.get("2105 CREDIT"));
        assertThat(debits).isEqualByComparingTo(credits);
        assertAmount(balanceOf(w.env(), w.student()), "948.75");
        assertThat(benefits).isNotNull();
    }

    @Test
    void theBenefitsAccountMayBeAContraRevenueOfTypeIncome() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "INCOME");
        approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);

        assertThat(accrue(w, "key-1").status()).isEqualTo("COMPLETED");
        assertAmount(charge(w).discount(), "275.00");
    }

    @Test
    void thePreviewAndTheSimulatorShowWhatTheRunCreatesAndTheSimulatorWritesOnlyItsAuditEntry() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        UUID award = approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);

        AccrualPreviewResult preview = preview(w);
        SimulateCharges.Simulation simulation = simulation().simulate(w.env().actor(), UUID.randomUUID().toString(),
                w.env().plan(), w.student(), FEB_1);

        assertThat(preview.blockers()).isEmpty();
        assertThat(preview.items()).singleElement().satisfies(item -> {
            assertAmount(item.base().amount(), "1100.00");
            assertAmount(item.benefit().amount(), "275.00");
            assertAmount(item.netBase().amount(), "825.00");
            assertAmount(item.tax().amount(), "123.75");
            assertAmount(item.total().amount(), "948.75");
            assertThat(item.awardId()).isEqualTo(award);
            assertThat(item.scholarshipCode()).isEqualTo("BECA-25");
        });
        assertThat(preview.totals()).singleElement().satisfies(total -> {
            assertAmount(total.benefit().amount(), "275.00");
            assertAmount(total.netBase().amount(), "825.00");
        });
        assertThat(simulation.periodLabel()).isEqualTo("2027-02");
        assertThat(simulation.lines()).singleElement().satisfies(line -> {
            assertThat(line.outcome()).isEqualTo(AccrualOutcome.CREATED);
            assertAmount(line.base().amount(), "1100.00");
            assertAmount(line.benefit().amount(), "275.00");
            assertAmount(line.netBase().amount(), "825.00");
            assertAmount(line.tax().amount(), "123.75");
            assertAmount(line.total().amount(), "948.75");
            assertThat(line.scholarshipAwardId()).isEqualTo(award);
            assertThat(line.scholarshipCode()).isEqualTo("BECA-25");
        });
        assertThat(charges(w)).isEmpty();
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = ?",
                w.institution(), "charges.simulation.viewed")).isEqualTo(1);
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and "
                + "action = ? and (after_value::text like '%275%' or after_value::text like '%BECA%')", w.institution(),
                "charges.simulation.viewed")).isZero();

        accrue(w, "key-1");
        assertAmount(charge(w).netBase(), "825.00");
    }

    @Test
    void theSimulatorRefusesAStudentThatIsNotInThePlanAPeriodThatIsNotOfThePlanAndAnotherInstitution() throws SQLException {
        World w = world();
        World other = world();

        assertThatThrownBy(() -> simulation().simulate(w.env().actor(), UUID.randomUUID().toString(), w.env().plan(),
                other.student(), FEB_1)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> simulation().simulate(other.env().actor(), UUID.randomUUID().toString(), w.env().plan(),
                other.student(), FEB_1)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> simulation().simulate(w.env().actor(), UUID.randomUUID().toString(), w.env().plan(),
                w.student(), LocalDate.parse("2027-02-15"))).isInstanceOf(com.confia.charges.domain.InvalidChargesValueException.class);
    }

    // --- The rule: edge cases ---

    @Test
    void aScholarshipOfAHundredPercentLeavesNothingToPayNoTaxAndAnEntryWithoutReceivable() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "BECA-100", percent(w, "100", null), FEB_1, JUN_30);

        assertThat(accrue(w, "key-1").status()).isEqualTo("COMPLETED");

        Stored stored = charge(w);
        assertAmount(stored.discount(), "1100.00");
        assertAmount(stored.netBase(), "0.00");
        assertAmount(stored.tax(), "0.00");
        assertAmount(stored.total(), "0.00");
        Map<String, BigDecimal> ledger = ledgerOfTheCharge(w);
        assertThat(ledger).containsOnlyKeys("4105 CREDIT", "5105 DEBIT");
        assertAmount(ledger.get("4105 CREDIT"), "1100.00");
        assertAmount(ledger.get("5105 DEBIT"), "1100.00");
        assertAmount(balanceOf(w.env(), w.student()), "0.00");
        night(w);
        assertThat(viewIntegrityRuns().latest(w.env().actor(), UUID.randomUUID().toString()).run().status()).isEqualTo("OK");
    }

    @Test
    void aChargeWithNothingToPayCanBeVoidedAndIsNotSomethingAPaymentCanCover() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "BECA-100", percent(w, "100", null), FEB_1, JUN_30);
        accrue(w, "key-1");
        UUID chargeId = chargesByStudent(w.env()).get(w.student());

        ChargeVoidView voided = voidCharge().voidCharge(w.env().actor(), UUID.randomUUID().toString(), "void-1", chargeId,
                REASON);

        assertThat(voided.chargeId()).isEqualTo(chargeId);
        night(w);
        assertThat(viewIntegrityRuns().latest(w.env().actor(), UUID.randomUUID().toString()).run().status()).isEqualTo("OK");
    }

    @Test
    void aFixedAmountGreaterThanTheBaseTakesTheBaseAndNoMore() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "FIJA-2000", fixed(w, "2000.00"), FEB_1, JUN_30);

        accrue(w, "key-1");

        Stored stored = charge(w);
        assertAmount(stored.discount(), "1100.00");
        assertAmount(stored.total(), "0.00");
    }

    @Test
    void theCapPerPeriodLimitsTheBenefitAndTheTaxIsOnWhatIsLeft() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "TOPE", percent(w, "50", "300.00"), FEB_1, JUN_30);

        accrue(w, "key-1");

        Stored stored = charge(w);
        assertAmount(stored.discount(), "300.00");
        assertAmount(stored.netBase(), "800.00");
        assertAmount(stored.tax(), "120.00");
        assertAmount(stored.total(), "920.00");
    }

    // --- The cap per period is shared by the charges of the award in the period (L8) ---

    private World twoConcepts(Concepts concepts) throws SQLException {
        Env env = seed(1, "1100.00", "15", "HNL", concepts);
        return new World(env, yearOf(env.institution()), actorOf(env.institution(), "academic_coordinator"),
                actorOf(env.institution(), "administrator"));
    }

    /** A scholarship of the percentage and the cap given that covers both concepts. */
    private ManageScholarships.Draft bothConcepts(World w, String points, String cap) throws SQLException {
        return new ManageScholarships.Draft("Beca del " + points + " %", "PERCENTAGE", points, null, cap,
                Set.of(w.env().concept(), secondConcept(w.env())));
    }

    private AccrualRunView accruePlan(World w, UUID plan, String key) {
        AccrualRunView run = manageAccrual().request(w.env().actor(), UUID.randomUUID().toString(), key, plan, FEBRUARY, null);
        executeAccrualRun().execute(w.env().system(), run.id());
        return manageAccrual().get(w.env().actor(), UUID.randomUUID().toString(), run.id(), 0, 200).run();
    }

    private UUID chargeOf(World w, UUID concept) throws SQLException {
        return firstId(w.institution(), "select id from charges_charge where institution_id = ? and payment_concept_id = ?",
                w.institution(), concept);
    }

    @Test
    void theCapIsSharedByTheTwoConceptsOfOneRunAndTheTaxIsOnWhatIsLeftOfEach() throws SQLException {
        World w = twoConcepts(Concepts.TWO_IN_ONE_PLAN);
        addBenefitsAccount(w.env(), "EXPENSE");
        UUID award = approvedAward(w, "TOPE", bothConcepts(w, "50", "600.00"), FEB_1, JUN_30);

        AccrualRunView run = accrue(w, "key-1");

        assertThat(run.chargesCreated()).isEqualTo(2);
        List<Stored> stored = new ArrayList<>(charges(w));
        stored.sort(java.util.Comparator.comparing(Stored::discount));
        // 50 % of 1,100.00 is 550.00 on the first charge; only 50.00 is left of the cap of 600.00 for the second.
        assertAmount(stored.get(0).discount(), "50.00");
        assertAmount(stored.get(0).netBase(), "1050.00");
        assertAmount(stored.get(0).tax(), "157.50");
        assertAmount(stored.get(0).total(), "1207.50");
        assertAmount(stored.get(1).discount(), "550.00");
        assertAmount(stored.get(1).netBase(), "550.00");
        assertAmount(stored.get(1).tax(), "82.50");
        assertAmount(stored.get(1).total(), "632.50");
        assertThat(stored).allSatisfy(charge -> assertThat(charge.awardId()).isEqualTo(award));
        assertAmount(stored.get(0).discount().add(stored.get(1).discount()), "600.00");
    }

    @Test
    void thePreviewAndTheSimulatorShowTheSharedCapAndTheRunCreatesWhatTheyShow() throws SQLException {
        World w = twoConcepts(Concepts.TWO_IN_ONE_PLAN);
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "TOPE", bothConcepts(w, "50", "600.00"), FEB_1, JUN_30);

        AccrualPreviewResult preview = preview(w);

        assertThat(preview.items()).hasSize(2);
        assertThat(preview.items()).extracting(item -> item.benefit().amount().toPlainString())
                .containsExactlyInAnyOrder("550.0000", "50.0000");
        assertAmount(preview.totals().get(0).benefit().amount(), "600.00");
        accrue(w, "key-1");
        assertThat(charges(w)).extracting(Stored::discount).extracting(BigDecimal::toPlainString)
                .containsExactlyInAnyOrder("550.0000", "50.0000");
    }

    @Test
    void theCapIsSharedAcrossTwoRunsOfTheSamePeriod() throws SQLException {
        World w = twoConcepts(Concepts.TWO_IN_TWO_PLANS);
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "TOPE", bothConcepts(w, "50", "600.00"), FEB_1, JUN_30);

        AccrualRunView first = accruePlan(w, w.env().plan(), "key-1");
        AccrualRunView second = accruePlan(w, secondPlan(w.env()), "key-2");

        assertThat(first.chargesCreated()).isEqualTo(1);
        assertThat(second.chargesCreated()).isEqualTo(1);
        List<Stored> stored = charges(w);
        assertThat(stored).hasSize(2);
        BigDecimal given = stored.get(0).discount().add(stored.get(1).discount());
        assertAmount(given, "600.00");
        assertThat(stored).extracting(Stored::discount).extracting(BigDecimal::toPlainString)
                .containsExactlyInAnyOrder("550.0000", "50.0000");
    }

    @Test
    void aVoidedChargeReleasesItsBenefitToTheNextChargeOfThePeriod() throws SQLException {
        World w = twoConcepts(Concepts.TWO_IN_TWO_PLANS);
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "TOPE", bothConcepts(w, "50", "600.00"), FEB_1, JUN_30);
        accruePlan(w, w.env().plan(), "key-1");
        voidCharge().voidCharge(w.env().actor(), UUID.randomUUID().toString(), "void-1", chargeOf(w, w.env().concept()), REASON);

        accruePlan(w, secondPlan(w.env()), "key-2");

        // The 550.00 of the voided charge no longer counts: the second charge takes its whole share, not the 50.00 that was left.
        assertThat(charges(w)).extracting(Stored::discount).extracting(BigDecimal::toPlainString)
                .containsExactly("550.0000", "550.0000");
    }

    @Test
    void theDatabaseRefusesACapThatTheEngineWouldExceedBecauseItIgnoredWhatTheAwardAlreadyGave() throws SQLException {
        World w = twoConcepts(Concepts.TWO_IN_TWO_PLANS);
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "TOPE", bothConcepts(w, "50", "600.00"), FEB_1, JUN_30);
        accruePlan(w, w.env().plan(), "key-1");
        // An engine that forgets what the award gave in the period offers 550.00 again.
        AwardBenefits real = awardBenefits();
        AwardBenefits forgetful = new AwardBenefits() {
            @Override
            public List<Offer> offers(InstitutionId institution, UUID student, UUID concept, LocalDate on, Money base,
                    java.util.function.Function<UUID, Money> alreadyGiven, boolean lock) {
                return real.offers(institution, student, concept, on, base, award -> Money.zero(CurrencyCode.HNL), lock);
            }

            @Override
            public boolean anyApplicable(InstitutionId institution, java.util.Collection<UUID> students,
                    java.util.Collection<UUID> concepts, LocalDate on) {
                return real.anyApplicable(institution, students, concepts, on);
            }
        };
        AccrualRunView run = manageAccrual().request(w.env().actor(), UUID.randomUUID().toString(), "key-2",
                secondPlan(w.env()), FEBRUARY, null);

        executeAccrualRun(forgetful).execute(w.env().system(), run.id());

        AccrualRunView after = manageAccrual().get(w.env().actor(), UUID.randomUUID().toString(), run.id(), 0, 200).run();
        assertThat(after.chargesCreated()).isZero();
        assertThat(after.chargesFailed()).isEqualTo(1);
        assertThat(charges(w)).hasSize(1);
    }

    @Test
    void theMidpointOfTheBenefitRoundsHalfUpOnceAndTheTaxOfTheReducedBaseToo() throws SQLException {
        // 12.5 % of 10.04 is exactly 1.255, which rounds up to 1.26; the net base is 8.78 and 10 % of it is 0.878, which is 0.88.
        World w = world("10.04", "10", "HNL");
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "MEDIO", percent(w, "12.50", null), FEB_1, JUN_30);

        accrue(w, "key-1");

        Stored stored = charge(w);
        assertAmount(stored.discount(), "1.26");
        assertAmount(stored.netBase(), "8.78");
        assertAmount(stored.tax(), "0.88");
        assertAmount(stored.total(), "9.66");
    }

    @Test
    void anAwardThatStartsOnTheDayOfTheChargeAndOneThatEndsThatDayBothApply() throws SQLException {
        World starting = world();
        addBenefitsAccount(starting.env(), "EXPENSE");
        approvedAward(starting, "DESDE", percent(starting, "25", null), FEB_1, JUN_30);
        World ending = world();
        addBenefitsAccount(ending.env(), "EXPENSE");
        approvedAward(ending, "HASTA", percent(ending, "25", null), JAN_1, FEB_1);

        accrue(starting, "key-1");
        accrue(ending, "key-1");

        assertAmount(charge(starting).discount(), "275.00");
        assertAmount(charge(ending).discount(), "275.00");
    }

    @Test
    void anAwardThatIsNotYetInForceOrHasExpiredIsNotApplied() throws SQLException {
        World notYet = world();
        addBenefitsAccount(notYet.env(), "EXPENSE");
        approvedAward(notYet, "FUTURA", percent(notYet, "25", null), LocalDate.parse("2027-03-01"), JUN_30);
        World expired = world();
        addBenefitsAccount(expired.env(), "EXPENSE");
        approvedAward(expired, "VENCIDA", percent(expired, "25", null), JAN_1, LocalDate.parse("2027-01-31"));

        accrue(notYet, "key-1");
        accrue(expired, "key-1");

        for (World w : List.of(notYet, expired)) {
            Stored stored = charge(w);
            assertAmount(stored.discount(), "0.00");
            assertAmount(stored.total(), "1265.00");
            assertThat(stored.awardId()).isNull();
            assertThat(stored.scholarshipCode()).isNull();
        }
    }

    @Test
    void aPendingARejectedAndARevokedAwardAreNotApplied() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        requestedAward(w, "PENDIENTE", percent(w, "10", null), FEB_1, JUN_30);
        UUID rejected = requestedAward(w, "RECHAZADA", percent(w, "20", null), FEB_1, JUN_30);
        decideAward().reject(w.administrator(), request(), "reject-1", rejected, REASON);
        UUID revoked = approvedAward(w, "REVOCADA", percent(w, "30", null), FEB_1, JUN_30);
        decideAward().revoke(w.administrator(), request(), "revoke-1", revoked, REASON);

        accrue(w, "key-1");

        Stored stored = charge(w);
        assertAmount(stored.discount(), "0.00");
        assertThat(stored.awardId()).isNull();
    }

    @Test
    void anInactiveScholarshipIsNoLongerApplied() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        var scholarship = manageScholarships().create(w.administrator(), request(), "BECA-25", percent(w, "25", null));
        UUID award = requestAward().request(w.coordinator(), request(),
                new RequestAward.Command(scholarship.id(), w.student(), w.year(), FEB_1, JUN_30)).id();
        decideAward().approve(w.administrator(), request(), "approve-1", award);
        manageScholarships().deactivate(w.administrator(), request(), scholarship.id());

        accrue(w, "key-1");

        assertAmount(charge(w).discount(), "0.00");
    }

    @Test
    void ofTwoAwardsOnTheSameChargeOnlyTheGreatestBenefitIsApplied() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "A-10", percent(w, "10", null), FEB_1, JUN_30);
        UUID great = approvedAward(w, "B-25", percent(w, "25", null), FEB_1, JUN_30);

        accrue(w, "key-1");

        Stored stored = charge(w);
        assertAmount(stored.discount(), "275.00");
        assertThat(stored.awardId()).isEqualTo(great);
        assertThat(stored.scholarshipCode()).isEqualTo("B-25");
        assertThat(ledgerOfTheCharge(w)).hasSize(4);
    }

    @Test
    void onATieTheScholarshipWithTheLowestCodeIsApplied() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "B-TIE", fixed(w, "200.00"), FEB_1, JUN_30);
        UUID lowest = approvedAward(w, "A-TIE", fixed(w, "200.00"), FEB_1, JUN_30);

        accrue(w, "key-1");

        Stored stored = charge(w);
        assertAmount(stored.discount(), "200.00");
        assertThat(stored.awardId()).isEqualTo(lowest);
        assertThat(stored.scholarshipCode()).isEqualTo("A-TIE");
    }

    @Test
    void aScholarshipThatDoesNotCoverTheConceptOfTheChargeIsNotApplied() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        UUID other = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution())) {
            exec(c, "insert into catalog_payment_concept (institution_id, id, code, name, category, is_taxable, is_recurring) "
                    + "values (?, ?, 'MAT', 'Material', 'MATERIAL', false, false)", w.institution(), other);
            c.commit();
        }
        approvedAward(w, "OTRO", new ManageScholarships.Draft("Beca de material", "PERCENTAGE", "25", null, null, Set.of(other)),
                FEB_1, JUN_30);

        accrue(w, "key-1");

        assertAmount(charge(w).discount(), "0.00");
    }

    @Test
    void aChargeInAnotherCurrencyThanHnlGetsNoBenefit() throws SQLException {
        World w = world("1100.00", "15", "USD");
        addBenefitsAccount(w.env(), "EXPENSE");
        approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);

        accrue(w, "key-1");

        Stored stored = charge(w);
        assertAmount(stored.discount(), "0.00");
        assertAmount(stored.total(), "1265.00");
        assertThat(stored.awardId()).isNull();
    }

    // --- The map of posting accounts ---

    @Test
    void aPeriodWithApplicableScholarshipsAndNoBenefitsAccountBlocksThePreviewAndRejectsTheRunWithNothingWritten()
            throws SQLException {
        World w = world();
        approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);

        assertThat(preview(w).blockers()).extracting(blocker -> blocker.code()).contains("posting-accounts-incomplete");
        AccrualRunView run = accrue(w, "key-1");

        assertThat(run.status()).isEqualTo("REJECTED");
        assertThat(run.failureCode()).isEqualTo("posting-accounts-incomplete");
        assertThat(charges(w)).isEmpty();
    }

    @Test
    void aBenefitsAccountOfAnotherTypeLeavesTheMapIncompleteForAPeriodWithScholarships() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "ASSET");
        approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);

        assertThat(preview(w).blockers()).extracting(blocker -> blocker.code()).contains("posting-accounts-incomplete");
    }

    @Test
    void withoutApplicableScholarshipsTheBenefitsAccountIsNotNeededAndNothingChanges() throws SQLException {
        World w = world();
        // A scholarship whose award is only pending, and one that is approved for another period: nothing applies in February.
        requestedAward(w, "PENDIENTE", percent(w, "10", null), FEB_1, JUN_30);

        assertThat(preview(w).blockers()).isEmpty();
        assertThat(accrue(w, "key-1").status()).isEqualTo("COMPLETED");
        Stored stored = charge(w);
        assertAmount(stored.discount(), "0.00");
        assertAmount(stored.total(), "1265.00");
        assertThat(ledgerOfTheCharge(w)).containsOnlyKeys("1105 DEBIT", "2105 CREDIT", "4105 CREDIT");
    }

    // --- Forward only, idempotency and the preview ---

    @Test
    void aChargeIssuedBeforeTheApprovalNeverChangesAndGeneratingAgainCreatesNothing() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        accrue(w, "key-1");
        approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);

        AccrualRunView again = accrue(w, "key-2");

        assertThat(again.chargesCreated()).isZero();
        assertThat(again.chargesExisting()).isEqualTo(1);
        Stored stored = charge(w);
        assertAmount(stored.discount(), "0.00");
        assertAmount(stored.total(), "1265.00");
    }

    @Test
    void revokingTheAwardAfterTheChargeWasIssuedChangesNothingAndGeneratingAgainIsIdempotent() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        UUID award = approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);
        accrue(w, "key-1");
        decideAward().revoke(w.administrator(), request(), "revoke-1", award, REASON);

        AccrualRunView again = accrue(w, "key-2");

        assertThat(again.chargesCreated()).isZero();
        assertThat(again.chargesExisting()).isEqualTo(1);
        Stored stored = charge(w);
        assertAmount(stored.discount(), "275.00");
        assertThat(stored.awardId()).isEqualTo(award);
    }

    @Test
    void anAwardThatChangesBetweenThePreviewAndTheConfirmationRejectsTheRunWithNothingWritten() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        UUID award = approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);
        AccrualPreviewResult preview = preview(w);
        decideAward().revoke(w.administrator(), request(), "revoke-1", award, REASON);

        AccrualRunView run = manageAccrual().request(w.env().actor(), UUID.randomUUID().toString(), "key-1", w.env().plan(),
                FEBRUARY, preview.previewDigest());
        execute(run.id(), w);

        AccrualRunView after = manageAccrual().get(w.env().actor(), UUID.randomUUID().toString(), run.id(), 0, 200).run();
        assertThat(after.status()).isEqualTo("REJECTED");
        assertThat(after.failureCode()).isEqualTo("preview-outdated");
        assertThat(charges(w)).isEmpty();
    }

    @Test
    void theStudentChargesAndTheNightlyCheckShowTheBenefitAndAreSound() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        UUID award = approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);
        accrue(w, "key-1");

        var page = listStudentCharges().list(w.env().actor(), UUID.randomUUID().toString(), w.student(),
                new ChargeFilter(null, null, null, false), new PageRequest(0, 50, "periodStart", true));

        assertThat(page.items()).singleElement().satisfies(view -> {
            var row = view.charge();
            assertAmount(row.base().amount(), "1100.00");
            assertAmount(row.benefit().amount(), "275.00");
            assertAmount(row.netBase().amount(), "825.00");
            assertAmount(row.tax().amount(), "123.75");
            assertAmount(row.total().amount(), "948.75");
            assertThat(row.scholarshipAwardId()).isEqualTo(award);
            assertThat(row.scholarshipCode()).isEqualTo("BECA-25");
            assertAmount(view.outstanding().amount(), "948.75");
        });
        night(w);
        assertThat(viewIntegrityRuns().latest(w.env().actor(), UUID.randomUUID().toString()).run().status()).isEqualTo("OK");
    }

    @Test
    void aStoredChargeCannotLoseItsAwardNorItsBenefit() throws SQLException {
        World w = world();
        addBenefitsAccount(w.env(), "EXPENSE");
        UUID award = approvedAward(w, "BECA-25", percent(w, "25", null), FEB_1, JUN_30);
        accrue(w, "key-1");
        UUID chargeId = chargesByStudent(w.env()).get(w.student());
        assertThat(chargeId).isNotNull();

        // The benefit and the award go together, whatever the writer.
        assertThatThrownBy(() -> {
            try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution())) {
                exec(c, "update charges_charge set scholarship_award_id = null where institution_id = ? and id = ?",
                        w.institution(), chargeId);
            }
        }).isInstanceOf(SQLException.class);
        assertThat(award).isNotNull();
    }

    private SimulateCharges simulation() {
        return new SimulateCharges(transactionRunner(), new com.confia.charges.infrastructure.JooqChargePlanRepository(dsl),
                new com.confia.charges.infrastructure.JooqChargePlanItemRepository(dsl),
                new com.confia.charges.infrastructure.JooqChargeRepository(dsl),
                new com.confia.organization.infrastructure.JooqPlacementDirectory(dsl),
                new com.confia.students.infrastructure.JooqEnrollmentRoster(dsl),
                new com.confia.students.infrastructure.JooqStudentDirectory(dsl),
                new com.confia.catalog.infrastructure.JooqPricingDirectory(dsl), awardBenefits(),
                new com.confia.shared.infrastructure.JooqAuditLogWriter(dsl));
    }

    private void night(World w) {
        integrityCheck().runFor(new InstitutionId(w.institution()),
                new RecurringRun(com.confia.charges.application.RunChargesIntegrityCheck.TASK_NAME, UUID.randomUUID(),
                        CLOCK.instant()));
    }
}
