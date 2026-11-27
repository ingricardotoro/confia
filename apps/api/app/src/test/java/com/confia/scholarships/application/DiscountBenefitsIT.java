package com.confia.scholarships.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.scholarships.ScholarshipsFixture;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.scholarships.infrastructure.JooqDiscountsInForceRepository;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.SecurityContext;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The discounts the accrual engine applies, read from a real PostgreSQL (docs/cortes/scholarships-discounts.md): a campaign discount
 * applies to every student on every day of its validity (both ends included), an agreement discount only to the students with an
 * approved assignment in force, a pending, rejected or revoked assignment gives nothing, an inactive discount or a concept it does
 * not cover gives nothing, a charge in another currency gets none, and one institution never sees the discounts of another because
 * of row-level security. People, codes and amounts are fictional.
 */
class DiscountBenefitsIT extends ScholarshipsFixture {

    private static final LocalDate FROM = LocalDate.parse("2027-01-01");
    private static final LocalDate TO = LocalDate.parse("2027-12-31");
    private static final LocalDate FEB = LocalDate.parse("2027-02-01");
    private static final LocalDate JUN = LocalDate.parse("2027-06-30");
    private static final Money BASE = Money.of("1000.00", CurrencyCode.HNL);

    private record World(Env env, AuthenticatedActor coordinator, AuthenticatedActor administrator) {

        UUID institution() {
            return env.institution();
        }

        UUID student(int index) {
            return env.students().get(index);
        }

        UUID concept() {
            return env.concept();
        }
    }

    private World world() throws SQLException {
        Env env = seed(2);
        return new World(env, actorOf(env.institution(), "academic_coordinator"), actorOf(env.institution(), "administrator"));
    }

    /** Creates the discount; a campaign one is approved by another person at once, as it applies only then. */
    private DiscountView discount(World w, String code, String kind, String points, boolean stackable, LocalDate from,
            LocalDate to) {
        return settled(w, manageDiscounts().create(w.administrator(), request(), code, kind,
                new ManageDiscounts.Draft("Descuento " + code, "PERCENTAGE", points, null, from, to, stackable,
                        Set.of(w.concept()))));
    }

    private DiscountView settled(World w, DiscountView created) {
        return created.approvalStatus().equals("pending") ? approvedByAnother(w.institution(), created) : created;
    }

    private DiscountView campaign(World w, String code, String points) {
        return discount(w, code, "campaign", points, true, FROM, TO);
    }

    private DiscountView agreement(World w, String code, String points) {
        return discount(w, code, "agreement", points, true, FROM, TO);
    }

    private AssignmentView requested(World w, DiscountView discount, int student, LocalDate from, LocalDate to) {
        return requestAssignment().request(w.coordinator(), request(),
                new RequestDiscountAssignment.Command(discount.id(), w.student(student), from, to));
    }

    private AssignmentView approved(World w, DiscountView discount, int student, LocalDate from, LocalDate to) {
        AssignmentView assignment = requested(w, discount, student, from, to);
        return decideAssignment().approve(w.administrator(), request(), "approve-" + UUID.randomUUID(), assignment.id());
    }

    private DiscountBenefits benefits() {
        return new DiscountBenefitsService(new JooqDiscountsInForceRepository(dsl));
    }

    /** What the discounts in force offer, asked inside a transaction of the institution of the session, as the accrual asks. */
    private List<DiscountBenefits.Applicable> inForce(UUID sessionInstitution, UUID institution, UUID student, UUID concept,
            LocalDate on, CurrencyCode currency, boolean lockShare) {
        var context = new SecurityContext("", "system", sessionInstitution.toString(), UUID.randomUUID().toString());
        return transactionRunner().execute(context,
                () -> benefits().inForce(new InstitutionId(institution), student, concept, on, currency, lockShare));
    }

    private List<DiscountBenefits.Applicable> inForce(World w, int student, LocalDate on) {
        return inForce(w.institution(), w.institution(), w.student(student), w.concept(), on, CurrencyCode.HNL, false);
    }

    private boolean applicable(UUID sessionInstitution, UUID institution, UUID student, UUID concept, LocalDate on) {
        var context = new SecurityContext("", "system", sessionInstitution.toString(), UUID.randomUUID().toString());
        return transactionRunner().execute(context,
                () -> benefits().anyApplicable(new InstitutionId(institution), List.of(student), List.of(concept), on));
    }

    @Test
    void aCampaignDiscountAppliesToEveryStudentOnEveryDayOfItsValidityBothEndsIncluded() throws SQLException {
        World w = world();
        DiscountView created = discount(w, "CAMP-10", "campaign", "10", true, FEB, JUN);

        for (int student = 0; student < 2; student++) {
            assertThat(inForce(w, student, FEB)).singleElement().satisfies(found -> {
                assertThat(found.discountId()).isEqualTo(created.id());
                assertThat(found.discountCode()).isEqualTo("CAMP-10");
                assertThat(found.assignmentId()).as("a campaign applies by itself, without an assignment").isNull();
                assertThat(found.stackable()).isTrue();
                assertThat(found.percentage()).isTrue();
                assertThat(found.on(BASE)).isEqualTo(Money.of("100.00", CurrencyCode.HNL));
            });
            assertThat(inForce(w, student, JUN)).hasSize(1);
        }
        assertThat(inForce(w, 0, FEB.minusDays(1))).isEmpty();
        assertThat(inForce(w, 0, JUN.plusDays(1))).isEmpty();
    }

    @Test
    void anAgreementDiscountWithoutAnAssignmentOrWithAPendingOneOffersNothing() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");

        assertThat(inForce(w, 0, FEB)).isEmpty();
        requested(w, created, 0, FEB, JUN);
        assertThat(inForce(w, 0, FEB)).as("a pending assignment gives nothing").isEmpty();
        assertThat(applicable(w.institution(), w.institution(), w.student(0), w.concept(), FEB)).isFalse();
    }

    @Test
    void anAgreementDiscountAppliesOnlyToTheStudentWithAnApprovedAssignmentInForceBothEndsIncluded() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");
        AssignmentView assignment = approved(w, created, 0, FEB, JUN);

        assertThat(inForce(w, 0, FEB)).singleElement().satisfies(found -> {
            assertThat(found.discountCode()).isEqualTo("CONV-10");
            assertThat(found.assignmentId()).as("the approved assignment that makes it apply").isEqualTo(assignment.id());
        });
        assertThat(inForce(w, 0, JUN)).hasSize(1);
        assertThat(inForce(w, 0, FEB.minusDays(1))).isEmpty();
        assertThat(inForce(w, 0, JUN.plusDays(1))).isEmpty();
        assertThat(inForce(w, 1, FEB)).as("another student gets nothing").isEmpty();
        assertThat(applicable(w.institution(), w.institution(), w.student(0), w.concept(), FEB)).isTrue();
        assertThat(applicable(w.institution(), w.institution(), w.student(1), w.concept(), FEB)).isFalse();
    }

    @Test
    void aRejectedAndARevokedAssignmentOfferNothing() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");
        AssignmentView rejected = requested(w, created, 0, FEB, JUN);
        decideAssignment().reject(w.administrator(), request(), "reject-1", rejected.id(), REASON);
        AssignmentView revoked = approved(w, created, 1, FEB, JUN);
        assertThat(inForce(w, 1, FEB)).hasSize(1);
        decideAssignment().revoke(w.administrator(), request(), "revoke-1", revoked.id(), REASON);

        assertThat(inForce(w, 0, FEB)).isEmpty();
        assertThat(inForce(w, 1, FEB)).isEmpty();
    }

    @Test
    void aDeactivatedDiscountOffersNothingAndOffersAgainWhenReactivated() throws SQLException {
        World w = world();
        DiscountView camp = campaign(w, "CAMP-10", "10");
        DiscountView conv = agreement(w, "CONV-10", "10");
        approved(w, conv, 0, FEB, JUN);
        assertThat(inForce(w, 0, FEB)).hasSize(2);

        manageDiscounts().deactivate(w.administrator(), request(), camp.id());
        manageDiscounts().deactivate(w.administrator(), request(), conv.id());
        assertThat(inForce(w, 0, FEB)).isEmpty();
        assertThat(applicable(w.institution(), w.institution(), w.student(0), w.concept(), FEB)).isFalse();

        // Reactivating a campaign discount is a proposal: it applies again only once another person approves it.
        DiscountView asked = manageDiscounts().reactivate(w.administrator(), request(), camp.id());
        manageDiscounts().reactivate(w.administrator(), request(), conv.id());
        assertThat(asked.active()).isFalse();
        assertThat(asked.pendingChange().reactivate()).isTrue();
        assertThat(inForce(w, 0, FEB)).hasSize(1);
        approvedByAnother(w.institution(), asked);
        assertThat(inForce(w, 0, FEB)).hasSize(2);
    }

    @Test
    void aConceptThatTheDiscountDoesNotCoverGetsNoDiscount() throws SQLException {
        World w = world();
        campaign(w, "CAMP-10", "10");

        assertThat(inForce(w.institution(), w.institution(), w.student(0), UUID.randomUUID(), FEB, CurrencyCode.HNL, false))
                .isEmpty();
        assertThat(applicable(w.institution(), w.institution(), w.student(0), UUID.randomUUID(), FEB)).isFalse();
    }

    @Test
    void aChargeInAnotherCurrencyThanHnlGetsNoDiscount() throws SQLException {
        World w = world();
        campaign(w, "CAMP-10", "10");

        assertThat(inForce(w.institution(), w.institution(), w.student(0), w.concept(), FEB, CurrencyCode.USD, false)).isEmpty();
    }

    @Test
    void everyDiscountInForceComesInTheOrderOfTheCascadeAndTheRowsCanBeLockedInShareMode() throws SQLException {
        World w = world();
        settled(w, manageDiscounts().create(w.administrator(), request(), "B-FIJO", "campaign",
                new ManageDiscounts.Draft("Fijo", "FIXED_AMOUNT", null, "50.00", FROM, TO, true, Set.of(w.concept()))));
        discount(w, "Z-PCT", "campaign", "5", true, FROM, TO);
        approved(w, agreement(w, "A-PCT", "10"), 0, FEB, JUN);

        List<DiscountBenefits.Applicable> found = inForce(w.institution(), w.institution(), w.student(0), w.concept(), FEB,
                CurrencyCode.HNL, true);

        assertThat(found).extracting(DiscountBenefits.Applicable::discountCode).containsExactly("A-PCT", "Z-PCT", "B-FIJO");
        assertThat(found.get(2).on(BASE)).isEqualTo(Money.of("50.00", CurrencyCode.HNL));
    }

    @Test
    void anInstitutionNeverSeesTheDiscountsOfAnother() throws SQLException {
        World mine = world();
        World other = world();
        campaign(mine, "CAMP-10", "10");
        approved(mine, agreement(mine, "CONV-10", "10"), 0, FEB, JUN);

        // With the session of the other institution the rows of mine do not exist, whatever the identifiers asked for.
        assertThat(inForce(other.institution(), mine.institution(), mine.student(0), mine.concept(), FEB, CurrencyCode.HNL, false))
                .isEmpty();
        assertThat(applicable(other.institution(), mine.institution(), mine.student(0), mine.concept(), FEB)).isFalse();
        assertThat(applicable(mine.institution(), mine.institution(), mine.student(0), mine.concept(), FEB)).isTrue();
        // And with its own session they are there.
        assertThat(inForce(mine, 0, FEB)).hasSize(2);
        assertThat(inForce(other, 0, FEB)).isEmpty();
    }
}
