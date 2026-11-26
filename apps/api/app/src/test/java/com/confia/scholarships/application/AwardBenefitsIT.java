package com.confia.scholarships.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.scholarships.ScholarshipsFixture;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.shared.security.AuthenticatedActor;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The awards the accrual engine applies, read from a real PostgreSQL (docs/cortes/scholarships-awards.md, INV-16): only an award that is
 * approved, in force on the date with both ends included, of an active scholarship that covers the payment concept gives an offer, a
 * charge in another currency than HNL gets none, and one institution never sees the awards of another because of row-level security.
 * People, codes and amounts are fictional.
 */
class AwardBenefitsIT extends ScholarshipsFixture {

    private static final LocalDate FEB = LocalDate.parse("2027-02-01");
    private static final LocalDate JUN = LocalDate.parse("2027-06-30");
    private static final Money BASE = Money.of("1100.00", CurrencyCode.HNL);

    private record World(Env env, UUID year, AuthenticatedActor coordinator, AuthenticatedActor administrator) {

        UUID institution() {
            return env.institution();
        }

        UUID student() {
            return env.students().get(0);
        }

        UUID concept() {
            return env.concept();
        }
    }

    private World world() throws SQLException {
        Env env = seed(1);
        return new World(env, yearOf(env.institution()), actorOf(env.institution(), "academic_coordinator"),
                actorOf(env.institution(), "administrator"));
    }

    private ScholarshipView scholarship(World w, String code, String points) {
        return manageScholarships().create(w.administrator(), request(), code,
                new ManageScholarships.Draft("Beca del " + points + " %", "PERCENTAGE", points, null, null, Set.of(w.concept())));
    }

    private AwardView requested(World w, ScholarshipView scholarship, LocalDate from, LocalDate to) {
        return requestAward().request(w.coordinator(), request(),
                new RequestAward.Command(scholarship.id(), w.student(), w.year(), from, to));
    }

    private AwardView approved(World w, ScholarshipView scholarship, LocalDate from, LocalDate to) {
        AwardView award = requested(w, scholarship, from, to);
        return decideAward().approve(w.administrator(), request(), "approve-" + UUID.randomUUID(), award.id());
    }

    /** What the awards in force offer, asked inside a transaction of the institution of the session, as the accrual asks. */
    private List<AwardBenefits.Offer> offers(UUID sessionInstitution, UUID institution, UUID student, UUID concept, LocalDate on,
            Money base, boolean lockShare) {
        var context = new com.confia.shared.security.SecurityContext("", "system", sessionInstitution.toString(),
                UUID.randomUUID().toString());
        return transactionRunner().execute(context,
                () -> awardBenefits().offers(new InstitutionId(institution), student, concept, on, base,
                        award -> Money.zero(CurrencyCode.HNL), lockShare));
    }

    /** Whether any award is applicable, asked inside a transaction of the institution of the session. */
    private boolean applicable(UUID sessionInstitution, UUID institution, UUID student, UUID concept, LocalDate on) {
        var context = new com.confia.shared.security.SecurityContext("", "system", sessionInstitution.toString(),
                UUID.randomUUID().toString());
        return transactionRunner().execute(context, () -> awardBenefits().anyApplicable(new InstitutionId(institution),
                List.of(student), List.of(concept), on));
    }

    private List<AwardBenefits.Offer> offers(World w, LocalDate on) {
        return offers(w.institution(), w.institution(), w.student(), w.concept(), on, BASE, false);
    }

    @Test
    void anApprovedAwardInForceOffersTheBenefitOfItsScholarshipOnTheBase() throws SQLException {
        World w = world();
        AwardView award = approved(w, scholarship(w, "BECA-25", "25"), FEB, JUN);

        assertThat(offers(w, FEB)).singleElement().satisfies(offer -> {
            assertThat(offer.awardId()).isEqualTo(award.id());
            assertThat(offer.scholarshipCode()).isEqualTo("BECA-25");
            assertThat(offer.amount()).isEqualTo(Money.of("275.00", CurrencyCode.HNL));
        });
    }

    @Test
    void theRowsCanBeLockedInShareModeByTheRunOfTheWorker() throws SQLException {
        World w = world();
        approved(w, scholarship(w, "BECA-25", "25"), FEB, JUN);

        List<AwardBenefits.Offer> locked = offers(w.institution(), w.institution(), w.student(), w.concept(), FEB, BASE, true);

        assertThat(locked).hasSize(1);
    }

    @Test
    void aPendingARejectedAndARevokedAwardOfferNothing() throws SQLException {
        World w = world();
        requested(w, scholarship(w, "PENDIENTE", "10"), FEB, JUN);
        AwardView rejected = requested(w, scholarship(w, "RECHAZADA", "20"), FEB, JUN);
        decideAward().reject(w.administrator(), request(), "reject-1", rejected.id(), REASON);
        AwardView revoked = approved(w, scholarship(w, "REVOCADA", "30"), FEB, JUN);
        decideAward().revoke(w.administrator(), request(), "revoke-1", revoked.id(), REASON);

        assertThat(offers(w, FEB)).isEmpty();
        assertThat(applicable(w.institution(), w.institution(), w.student(), w.concept(), FEB)).isFalse();
    }

    @Test
    void theValidityIncludesItsFirstAndItsLastDayAndNothingOutsideIt() throws SQLException {
        World w = world();
        approved(w, scholarship(w, "BECA-25", "25"), LocalDate.parse("2027-02-10"), LocalDate.parse("2027-02-20"));

        assertThat(offers(w, LocalDate.parse("2027-02-09"))).isEmpty();
        assertThat(offers(w, LocalDate.parse("2027-02-10"))).hasSize(1);
        assertThat(offers(w, LocalDate.parse("2027-02-20"))).hasSize(1);
        assertThat(offers(w, LocalDate.parse("2027-02-21"))).isEmpty();
    }

    @Test
    void aDeactivatedScholarshipOffersNothingAndOffersAgainWhenReactivated() throws SQLException {
        World w = world();
        ScholarshipView scholarship = scholarship(w, "BECA-25", "25");
        approved(w, scholarship, FEB, JUN);

        manageScholarships().deactivate(w.administrator(), request(), scholarship.id());
        assertThat(offers(w, FEB)).isEmpty();

        manageScholarships().reactivate(w.administrator(), request(), scholarship.id());
        assertThat(offers(w, FEB)).hasSize(1);
    }

    @Test
    void aConceptThatTheScholarshipDoesNotCoverGetsNoOfferAndAnotherStudentGetsNone() throws SQLException {
        World w = world();
        approved(w, scholarship(w, "BECA-25", "25"), FEB, JUN);

        assertThat(offers(w.institution(), w.institution(), w.student(), UUID.randomUUID(), FEB, BASE, false)).isEmpty();
        assertThat(offers(w.institution(), w.institution(), UUID.randomUUID(), w.concept(), FEB, BASE, false)).isEmpty();
        assertThat(applicable(w.institution(), w.institution(), UUID.randomUUID(), w.concept(), FEB)).isFalse();
        assertThat(applicable(w.institution(), w.institution(), w.student(), w.concept(), FEB)).isTrue();
    }

    @Test
    void aBaseInAnotherCurrencyThanHnlGetsNoOffer() throws SQLException {
        World w = world();
        approved(w, scholarship(w, "BECA-25", "25"), FEB, JUN);

        assertThat(offers(w.institution(), w.institution(), w.student(), w.concept(), FEB,
                Money.of("1100.00", CurrencyCode.USD), false)).isEmpty();
    }

    @Test
    void everyAwardInForceOffersItsOwnBenefitInTheOrderOfTheCodeOfItsScholarship() throws SQLException {
        World w = world();
        approved(w, scholarship(w, "B-25", "25"), FEB, JUN);
        approved(w, scholarship(w, "A-10", "10"), FEB, JUN);

        assertThat(offers(w, FEB)).extracting(AwardBenefits.Offer::scholarshipCode).containsExactly("A-10", "B-25");
    }

    @Test
    void anInstitutionNeverSeesTheAwardsOfAnother() throws SQLException {
        World mine = world();
        World other = world();
        approved(mine, scholarship(mine, "BECA-25", "25"), FEB, JUN);

        // With the session of the other institution the rows of mine do not exist, whatever the identifiers asked for.
        assertThat(offers(other.institution(), mine.institution(), mine.student(), mine.concept(), FEB, BASE, false)).isEmpty();
        assertThat(applicable(other.institution(), mine.institution(), mine.student(), mine.concept(), FEB)).isFalse();
        assertThat(applicable(mine.institution(), mine.institution(), mine.student(), mine.concept(), FEB)).isTrue();
        // And with its own session they are there.
        assertThat(offers(mine.institution(), mine.institution(), mine.student(), mine.concept(), FEB, BASE, false)).hasSize(1);
        assertThat(offers(other, FEB)).isEmpty();
    }
}
