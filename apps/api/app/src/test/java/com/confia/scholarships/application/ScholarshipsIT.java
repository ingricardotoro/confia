package com.confia.scholarships.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.scholarships.ScholarshipsFixture;
import com.confia.scholarships.domain.AwardOverlapException;
import com.confia.scholarships.domain.DuplicateScholarshipCodeException;
import com.confia.scholarships.domain.IdempotencyKeyRequiredException;
import com.confia.scholarships.domain.InactiveReferenceException;
import com.confia.scholarships.domain.InvalidAwardStateException;
import com.confia.scholarships.domain.InvalidScholarshipsValueException;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.scholarships.domain.ScholarshipBenefitLockedException;
import com.confia.scholarships.domain.SelfApprovalForbiddenException;
import com.confia.scholarships.domain.StaleVersionException;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The scholarships and their awards against a real PostgreSQL (docs/cortes/scholarships-awards.md): the definition and the edit of a
 * scholarship, the lock of its benefit once an award was approved, the request of an award, the approval by another person
 * (segregation of duties), the rejection and the revocation with their reasons, the two approved awards of one scholarship for one
 * student that never overlap, the keys of the decisions, two decisions at the same time, the audit entries and the isolation between
 * institutions. People, codes and amounts are fictional.
 */
class ScholarshipsIT extends ScholarshipsFixture {

    private static final LocalDate FEB = LocalDate.parse("2027-02-01");
    private static final LocalDate JUN = LocalDate.parse("2027-06-30");

    private record World(Env env, UUID year, AuthenticatedActor coordinator, AuthenticatedActor administrator,
            AuthenticatedActor otherAdministrator, AuthenticatedActor cashier) {

        UUID institution() {
            return env.institution();
        }

        UUID student(int index) {
            return env.students().get(index);
        }
    }

    private World world() throws SQLException {
        Env env = seed(2);
        UUID institution = env.institution();
        return new World(env, yearOf(institution), actorOf(institution, "academic_coordinator"),
                actorOf(institution, "administrator"), actorOf(institution, "super_admin"), actorOf(institution, "cashier"));
    }

    private ManageScholarships.Draft percent(World w, String points) {
        return new ManageScholarships.Draft("Beca del " + points + " %", "PERCENTAGE", points, null, null,
                Set.of(w.env().concept()));
    }

    private ScholarshipView scholarship(World w, String code, String points) {
        return manageScholarships().create(w.administrator(), request(), code, percent(w, points));
    }

    private AwardView requested(World w, ScholarshipView scholarship, int student, LocalDate from, LocalDate to) {
        return requestAward().request(w.coordinator(), request(), new RequestAward.Command(scholarship.id(), w.student(student),
                w.year(), from, to));
    }

    private AwardView approved(World w, AwardView award) {
        return decideAward().approve(w.administrator(), request(), "approve-" + UUID.randomUUID(), award.id());
    }

    private long auditEntries(World w, String action) throws SQLException {
        return scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = ?",
                w.institution(), action);
    }

    // --- Scholarships ---

    @Test
    void aScholarshipIsCreatedWithItsBenefitCapAndConceptsAndAudited() throws SQLException {
        World w = world();

        ScholarshipView created = manageScholarships().create(w.administrator(), request(), "BECA-25",
                new ManageScholarships.Draft("  Beca del 25 %  ", "PERCENTAGE", "25", null, "300.00", Set.of(w.env().concept())));

        assertThat(created.code()).isEqualTo("BECA-25");
        assertThat(created.name()).isEqualTo("Beca del 25 %");
        assertThat(created.benefitType()).isEqualTo("PERCENTAGE");
        assertThat(created.percent()).isEqualTo("25.00");
        assertThat(created.fixedAmount()).isNull();
        assertThat(created.periodCap().compareTo(Money.of("300.00", CurrencyCode.HNL))).isZero();
        assertThat(created.concepts()).extracting(ScholarshipView.ConceptView::code).containsExactly("COL");
        assertThat(created.active()).isTrue();
        assertThat(created.version()).isZero();
        assertThat(created.benefitLocked()).isFalse();
        assertThat(auditEntries(w, "scholarships.scholarship.created")).isEqualTo(1);
        assertThat(manageScholarships().get(w.cashier(), request(), created.id()).code()).isEqualTo("BECA-25");
    }

    @Test
    void aFixedAmountScholarshipKeepsItsAmountAndHasNoPercentage() throws SQLException {
        World w = world();

        ScholarshipView created = manageScholarships().create(w.administrator(), request(), "FIJA-300",
                new ManageScholarships.Draft("Beca fija", "FIXED_AMOUNT", null, "300.00", null, Set.of(w.env().concept())));

        assertThat(created.percent()).isNull();
        assertThat(created.fixedAmount().compareTo(Money.of("300.00", CurrencyCode.HNL))).isZero();
        assertThat(created.periodCap()).isNull();
    }

    @Test
    void aCodeIsUniqueInTheInstitutionWhateverItsCapitals() throws SQLException {
        World w = world();
        scholarship(w, "BECA-25", "25");

        assertThatThrownBy(() -> scholarship(w, "beca-25", "10")).isInstanceOf(DuplicateScholarshipCodeException.class);
    }

    @Test
    void aValueOutOfItsRuleOrAConceptThatDoesNotExistIsRefused() throws SQLException {
        World w = world();

        assertThatThrownBy(() -> scholarship(w, "B1", "100.01")).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> manageScholarships().create(w.administrator(), request(), "B2",
                new ManageScholarships.Draft("N", "PERCENTAGE", "5", "5.00", null, Set.of(w.env().concept()))))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> manageScholarships().create(w.administrator(), request(), "B3",
                new ManageScholarships.Draft("N", "PERCENTAGE", "5", null, null, Set.of(UUID.randomUUID()))))
                .isInstanceOf(InvalidScholarshipsValueException.class)
                .satisfies(e -> assertThat(((InvalidScholarshipsValueException) e).field()).isEqualTo("conceptIds"));
        assertThat(manageScholarships().list(w.administrator(), request(), new ScholarshipFilter(null, null),
                PageRequest.of(0, 50, null, ScholarshipFilter.SORT_FIELDS, "code", true)).total()).isZero();
    }

    @Test
    void anEditNeedsTheVersionAndAdvancesIt() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");

        ScholarshipView edited = manageScholarships().update(w.administrator(), request(), created.id(), 0,
                new ManageScholarships.Draft("Otro nombre", "PERCENTAGE", "30", null, "100.00", Set.of(w.env().concept())));

        assertThat(edited.version()).isEqualTo(1);
        assertThat(edited.percent()).isEqualTo("30.00");
        assertThat(edited.name()).isEqualTo("Otro nombre");
        assertThat(auditEntries(w, "scholarships.scholarship.updated")).isEqualTo(1);
        assertThatThrownBy(() -> manageScholarships().update(w.administrator(), request(), created.id(), 0,
                percent(w, "30"))).isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> manageScholarships().update(w.administrator(), request(), UUID.randomUUID(), 0,
                percent(w, "30"))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void onceAnAwardWasApprovedTheValueOfTheBenefitDoesNotChangeButTheNameDoes() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");
        approved(w, requested(w, created, 0, FEB, JUN));

        assertThatThrownBy(() -> manageScholarships().update(w.administrator(), request(), created.id(), 0,
                percent(w, "30"))).isInstanceOf(ScholarshipBenefitLockedException.class);
        assertThatThrownBy(() -> manageScholarships().update(w.administrator(), request(), created.id(), 0,
                new ManageScholarships.Draft("N", "FIXED_AMOUNT", null, "25.00", null, Set.of(w.env().concept()))))
                .isInstanceOf(ScholarshipBenefitLockedException.class);
        ScholarshipView renamed = manageScholarships().update(w.administrator(), request(), created.id(), 0,
                new ManageScholarships.Draft("Solo el nombre", "PERCENTAGE", "25.00", null, "50.00",
                        Set.of(w.env().concept())));
        assertThat(renamed.name()).isEqualTo("Solo el nombre");
        assertThat(renamed.benefitLocked()).isTrue();
        assertThat(manageScholarships().get(w.administrator(), request(), created.id()).percent()).isEqualTo("25.00");
    }

    @Test
    void theValueStaysLockedWhenTheOnlyApprovedAwardWasRevoked() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");
        AwardView award = approved(w, requested(w, created, 0, FEB, JUN));
        decideAward().revoke(w.administrator(), request(), "revoke-1", award.id(), REASON);

        assertThatThrownBy(() -> manageScholarships().update(w.administrator(), request(), created.id(), 0,
                percent(w, "30"))).isInstanceOf(ScholarshipBenefitLockedException.class);
    }

    @Test
    void aScholarshipIsDeactivatedAndReactivatedAndNeverDeleted() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");

        ScholarshipView off = manageScholarships().deactivate(w.administrator(), request(), created.id());
        ScholarshipView same = manageScholarships().deactivate(w.administrator(), request(), created.id());
        ScholarshipView on = manageScholarships().reactivate(w.administrator(), request(), created.id());

        assertThat(off.active()).isFalse();
        assertThat(off.version()).isEqualTo(1);
        assertThat(same.version()).as("a deactivation of an inactive scholarship changes nothing").isEqualTo(1);
        assertThat(on.active()).isTrue();
        assertThat(on.version()).isEqualTo(2);
        assertThat(auditEntries(w, "scholarships.scholarship.deactivated")).isEqualTo(1);
        assertThat(auditEntries(w, "scholarships.scholarship.reactivated")).isEqualTo(1);
        assertThat(scalar(w.institution(), "select count(*) from scholarships_scholarship where institution_id = ?",
                w.institution())).isEqualTo(1);
    }

    @Test
    void theListIsFilteredSortedAndPagedByTheServer() throws SQLException {
        World w = world();
        scholarship(w, "B-C", "10");
        ScholarshipView b = scholarship(w, "B-A", "20");
        scholarship(w, "B-B", "30");
        manageScholarships().deactivate(w.administrator(), request(), b.id());

        Page<ScholarshipView> all = manageScholarships().list(w.cashier(), request(), new ScholarshipFilter(null, null),
                PageRequest.of(0, 2, "code,asc", ScholarshipFilter.SORT_FIELDS, "code", true));
        Page<ScholarshipView> inactive = manageScholarships().list(w.cashier(), request(), new ScholarshipFilter(false, null),
                PageRequest.of(0, 50, null, ScholarshipFilter.SORT_FIELDS, "code", true));
        Page<ScholarshipView> descending = manageScholarships().list(w.cashier(), request(), new ScholarshipFilter(true, "PERCENTAGE"),
                PageRequest.of(0, 50, "code,desc", ScholarshipFilter.SORT_FIELDS, "code", true));

        assertThat(all.total()).isEqualTo(3);
        assertThat(all.items()).extracting(ScholarshipView::code).containsExactly("B-A", "B-B");
        assertThat(inactive.items()).extracting(ScholarshipView::code).containsExactly("B-A");
        assertThat(descending.items()).extracting(ScholarshipView::code).containsExactly("B-C", "B-B");
        assertThatThrownBy(() -> manageScholarships().list(w.cashier(), request(), new ScholarshipFilter(null, "NOPE"),
                PageRequest.of(0, 50, null, ScholarshipFilter.SORT_FIELDS, "code", true)))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> PageRequest.of(0, 201, null, ScholarshipFilter.SORT_FIELDS, "code", true))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> PageRequest.of(0, 10, "password", ScholarshipFilter.SORT_FIELDS, "code", true))
                .isInstanceOf(InvalidScholarshipsValueException.class);
    }

    // --- Requesting an award ---

    @Test
    void anAwardIsBornPendingAndAudited() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");

        AwardView award = requested(w, created, 0, FEB, JUN);

        assertThat(award.status()).isEqualTo("pending");
        assertThat(award.scholarshipCode()).isEqualTo("BECA-25");
        assertThat(award.studentCode()).startsWith("E-");
        assertThat(award.requestedBy()).isEqualTo(w.coordinator().accountId());
        assertThat(award.decision()).isNull();
        assertThat(auditEntries(w, "scholarships.award.requested")).isEqualTo(1);
    }

    @Test
    void anAwardOutsideTheAcademicYearOrForAnInactiveScholarshipOrAnUnknownStudentIsRefused() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");

        assertThatThrownBy(() -> requested(w, created, 0, LocalDate.parse("2026-12-31"), JUN))
                .isInstanceOf(InvalidScholarshipsValueException.class)
                .satisfies(e -> assertThat(((InvalidScholarshipsValueException) e).reason()).isEqualTo("outside-academic-year"));
        assertThatThrownBy(() -> requested(w, created, 0, FEB, LocalDate.parse("2027-12-01")))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> requested(w, created, 0, JUN, FEB)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> requestAward().request(w.coordinator(), request(), new RequestAward.Command(created.id(),
                UUID.randomUUID(), w.year(), FEB, JUN))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> requestAward().request(w.coordinator(), request(), new RequestAward.Command(created.id(),
                w.student(0), UUID.randomUUID(), FEB, JUN))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> requestAward().request(w.coordinator(), request(), new RequestAward.Command(UUID.randomUUID(),
                w.student(0), w.year(), FEB, JUN))).isInstanceOf(ResourceNotFoundException.class);
        manageScholarships().deactivate(w.administrator(), request(), created.id());
        assertThatThrownBy(() -> requested(w, created, 0, FEB, JUN)).isInstanceOf(InactiveReferenceException.class);
        assertThat(scalar(w.institution(), "select count(*) from scholarships_award where institution_id = ?", w.institution()))
                .isZero();
    }

    // --- Approving ---

    @Test
    void whoRequestedAnAwardCannotApproveNorRejectItAndNothingChanges() throws SQLException {
        World w = world();
        AwardView award = requested(w, scholarship(w, "BECA-25", "25"), 0, FEB, JUN);

        assertThatThrownBy(() -> decideAward().approve(w.coordinator(), request(), "self-1", award.id()))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> decideAward().reject(w.coordinator(), request(), "self-2", award.id(), REASON))
                .isInstanceOf(SelfApprovalForbiddenException.class);

        assertThat(viewAwards().get(w.administrator(), request(), award.id()).status()).isEqualTo("pending");
        assertThat(scalar(w.institution(), "select count(*) from scholarships_award_decision where institution_id = ?",
                w.institution())).isZero();
    }

    @Test
    void theDatabaseItselfRefusesADecisionByTheRequesterWhateverTheCodeSays() throws SQLException {
        World w = world();
        AwardView award = requested(w, scholarship(w, "BECA-25", "25"), 0, FEB, JUN);

        assertThatThrownBy(() -> transactionRunner().execute(w.coordinator().securityContext(request()), () -> {
            new com.confia.scholarships.infrastructure.JooqAwardDecisionRepository(dsl).insert(
                    new com.confia.kernel.InstitutionId(w.institution()),
                    new com.confia.scholarships.domain.AwardDecision(UUID.randomUUID(), award.id(),
                            com.confia.scholarships.domain.DecisionKind.APPROVED, null, w.coordinator().accountId(),
                            NOW.instant(), "raw-1", "a".repeat(64)));
            return null;
        })).isInstanceOf(SelfApprovalForbiddenException.class);
    }

    @Test
    void anotherPersonApprovesAndTheDecisionIsAuditedWithTheApprover() throws SQLException {
        World w = world();
        AwardView award = requested(w, scholarship(w, "BECA-25", "25"), 0, FEB, JUN);

        AwardView approved = decideAward().approve(w.administrator(), request(), "approve-1", award.id());

        assertThat(approved.status()).isEqualTo("approved");
        assertThat(approved.decision().kind()).isEqualTo("approved");
        assertThat(approved.decision().decidedBy()).isEqualTo(w.administrator().accountId());
        assertThat(approved.decision().reason()).isNull();
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = "
                + "'scholarships.award.approved' and approver_id = ?", w.institution(), w.administrator().accountId()))
                .isEqualTo(1);
    }

    @Test
    void aSecondApprovalOrARejectionOfAnApprovedAwardIsAStateError() throws SQLException {
        World w = world();
        AwardView award = approved(w, requested(w, scholarship(w, "BECA-25", "25"), 0, FEB, JUN));

        assertThatThrownBy(() -> decideAward().approve(w.otherAdministrator(), request(), "again-1", award.id()))
                .isInstanceOf(InvalidAwardStateException.class);
        assertThatThrownBy(() -> decideAward().reject(w.otherAdministrator(), request(), "again-2", award.id(), REASON))
                .isInstanceOf(InvalidAwardStateException.class);
    }

    @Test
    void anAwardOfAnInactiveScholarshipIsNotApproved() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");
        AwardView award = requested(w, created, 0, FEB, JUN);
        manageScholarships().deactivate(w.administrator(), request(), created.id());

        assertThatThrownBy(() -> approved(w, award)).isInstanceOf(InactiveReferenceException.class);
        assertThat(viewAwards().get(w.administrator(), request(), award.id()).status()).isEqualTo("pending");
    }

    // --- Overlap ---

    @Test
    void twoApprovedAwardsOfOneScholarshipForOneStudentNeverOverlap() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");
        AwardView first = requested(w, created, 0, FEB, JUN);
        AwardView overlapping = requested(w, created, 0, LocalDate.parse("2027-06-30"), LocalDate.parse("2027-10-31"));
        AwardView after = requested(w, created, 0, LocalDate.parse("2027-07-01"), LocalDate.parse("2027-10-31"));
        approved(w, first);

        assertThatThrownBy(() -> approved(w, overlapping)).isInstanceOf(AwardOverlapException.class);

        assertThat(viewAwards().get(w.administrator(), request(), overlapping.id()).status())
                .as("the award that overlapped stays pending").isEqualTo("pending");
        assertThat(approved(w, after).status()).as("the next day does not overlap").isEqualTo("approved");
    }

    @Test
    void anOverlapIsAllowedForAnotherStudentAnotherScholarshipOrAfterARevocation() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");
        ScholarshipView other = scholarship(w, "BECA-10", "10");
        AwardView first = approved(w, requested(w, created, 0, FEB, JUN));

        assertThat(approved(w, requested(w, created, 1, FEB, JUN)).status()).isEqualTo("approved");
        assertThat(approved(w, requested(w, other, 0, FEB, JUN)).status()).isEqualTo("approved");
        decideAward().revoke(w.administrator(), request(), "revoke-1", first.id(), REASON);
        assertThat(approved(w, requested(w, created, 0, FEB, JUN)).status()).isEqualTo("approved");
    }

    @Test
    void aPendingOrRejectedAwardDoesNotBlockAnApprovedOne() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");
        AwardView pending = requested(w, created, 0, FEB, JUN);
        AwardView toReject = requested(w, created, 0, FEB, JUN);
        decideAward().reject(w.administrator(), request(), "reject-1", toReject.id(), REASON);

        assertThat(approved(w, requested(w, created, 0, FEB, JUN)).status()).isEqualTo("approved");
        assertThat(viewAwards().get(w.administrator(), request(), pending.id()).status()).isEqualTo("pending");
    }

    // --- Rejecting and revoking ---

    @Test
    void aRejectionKeepsItsReasonOnlyInTheDecisionAndTheAuditCarriesItsLengthOnly() throws SQLException {
        World w = world();
        AwardView award = requested(w, scholarship(w, "BECA-25", "25"), 0, FEB, JUN);

        AwardView rejected = decideAward().reject(w.administrator(), request(), "reject-1", award.id(), "  " + REASON + "  ");

        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(rejected.decision().reason()).isEqualTo(REASON);
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and (after_value::text "
                + "like ? or before_value::text like ? or coalesce(reason, '') like ?)", w.institution(), "%familia%",
                "%familia%", "%familia%")).isZero();
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = "
                + "'scholarships.award.rejected' and (after_value->>'reasonLength')::int = ?", w.institution(), REASON.length()))
                .isEqualTo(1);
        assertThatThrownBy(() -> approved(w, award)).isInstanceOf(InvalidAwardStateException.class);
    }

    @Test
    void aReasonOutOfItsBoundsIsRefusedBeforeAnythingIsWritten() throws SQLException {
        World w = world();
        AwardView award = requested(w, scholarship(w, "BECA-25", "25"), 0, FEB, JUN);

        assertThatThrownBy(() -> decideAward().reject(w.administrator(), request(), "short", award.id(), "x".repeat(19)))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> decideAward().reject(w.administrator(), request(), "long", award.id(), "x".repeat(501)))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThat(viewAwards().get(w.administrator(), request(), award.id()).status()).isEqualTo("pending");
    }

    @Test
    void anApprovedAwardIsRevokedWithAReasonAndAPendingOneIsNot() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");
        AwardView award = approved(w, requested(w, created, 0, FEB, JUN));
        AwardView pending = requested(w, created, 1, FEB, JUN);

        AwardView revoked = decideAward().revoke(w.administrator(), request(), "revoke-1", award.id(), REASON);

        assertThat(revoked.status()).isEqualTo("revoked");
        assertThat(revoked.decision().kind()).isEqualTo("approved");
        assertThat(revoked.revocation().kind()).isEqualTo("revoked");
        assertThat(revoked.revocation().reason()).isEqualTo(REASON);
        assertThat(auditEntries(w, "scholarships.award.revoked")).isEqualTo(1);
        assertThatThrownBy(() -> decideAward().revoke(w.administrator(), request(), "revoke-2", pending.id(), REASON))
                .isInstanceOf(InvalidAwardStateException.class);
        assertThatThrownBy(() -> decideAward().revoke(w.administrator(), request(), "revoke-3", award.id(), REASON))
                .isInstanceOf(InvalidAwardStateException.class);
    }

    // --- The keys ---

    @Test
    void theSameKeyAndTheSameRequestAnswerTheSameDecisionOnce() throws SQLException {
        World w = world();
        AwardView award = requested(w, scholarship(w, "BECA-25", "25"), 0, FEB, JUN);

        AwardView first = decideAward().approve(w.administrator(), request(), "approve-1", award.id());
        AwardView replay = decideAward().approve(w.administrator(), request(), "approve-1", award.id());

        assertThat(replay.decision().id()).isEqualTo(first.decision().id());
        assertThat(replay.status()).isEqualTo("approved");
        assertThat(scalar(w.institution(), "select count(*) from scholarships_award_decision where institution_id = ?",
                w.institution())).isEqualTo(1);
        assertThat(auditEntries(w, "scholarships.award.approved")).isEqualTo(1);
    }

    @Test
    void theSameKeyForAnotherRequestOrByAnotherPersonIsAMismatchAndAMissingKeyIsRefused() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");
        AwardView award = requested(w, created, 0, FEB, JUN);
        AwardView other = requested(w, created, 1, FEB, JUN);
        decideAward().approve(w.administrator(), request(), "approve-1", award.id());

        assertThatThrownBy(() -> decideAward().approve(w.administrator(), request(), "approve-1", other.id()))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> decideAward().approve(w.otherAdministrator(), request(), "approve-1", award.id()))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> decideAward().reject(w.administrator(), request(), "approve-1", other.id(), REASON))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> decideAward().approve(w.administrator(), request(), null, other.id()))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> decideAward().approve(w.administrator(), request(), "  ", other.id()))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> decideAward().approve(w.administrator(), request(), "has space", other.id()))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThat(viewAwards().get(w.administrator(), request(), other.id()).status()).isEqualTo("pending");
    }

    @Test
    void twoDecisionsOnTheSameAwardAtTheSameTimeLeaveExactlyOneWinner() throws Exception {
        World w = world();
        AwardView award = requested(w, scholarship(w, "BECA-25", "25"), 0, FEB, JUN);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(() -> {
            go.await();
            try {
                decideAward().approve(w.administrator(), request(), "race-approve", award.id());
                return "approved";
            } catch (InvalidAwardStateException e) {
                return "lost";
            }
        });
        tasks.add(() -> {
            go.await();
            try {
                decideAward().reject(w.otherAdministrator(), request(), "race-reject", award.id(), REASON);
                return "rejected";
            } catch (InvalidAwardStateException e) {
                return "lost";
            }
        });
        try {
            List<Future<String>> running = new ArrayList<>();
            tasks.forEach(task -> running.add(pool.submit(task)));
            go.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : running) {
                results.add(future.get());
            }
            assertThat(results).containsOnlyOnce("lost");
            assertThat(scalar(w.institution(), "select count(*) from scholarships_award_decision where institution_id = ?",
                    w.institution())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Reading and isolation ---

    @Test
    void theListOfAwardsIsFilteredByStudentStatusAndScholarship() throws SQLException {
        World w = world();
        ScholarshipView created = scholarship(w, "BECA-25", "25");
        ScholarshipView other = scholarship(w, "BECA-10", "10");
        AwardView a = requested(w, created, 0, FEB, JUN);
        requested(w, created, 1, FEB, JUN);
        requested(w, other, 0, FEB, JUN);
        approved(w, a);
        PageRequest page = PageRequest.of(0, 50, "validFrom,desc", AwardFilter.SORT_FIELDS, "requestedAt", false);

        assertThat(viewAwards().list(w.cashier(), request(), new AwardFilter(null, null, null, null), page).total()).isEqualTo(3);
        assertThat(viewAwards().list(w.cashier(), request(), new AwardFilter(w.student(0), null, null, null), page).total())
                .isEqualTo(2);
        assertThat(viewAwards().list(w.cashier(), request(), new AwardFilter(null, "approved", null, null), page).items())
                .extracting(AwardView::id).containsExactly(a.id());
        assertThat(viewAwards().list(w.cashier(), request(), new AwardFilter(null, null, other.id(), null), page).items())
                .extracting(AwardView::scholarshipCode).containsExactly("BECA-10");
        assertThatThrownBy(() -> viewAwards().list(w.cashier(), request(), new AwardFilter(null, "nope", null, null), page))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> PageRequest.of(0, 50, "studentId", AwardFilter.SORT_FIELDS, "requestedAt", false))
                .isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void anotherInstitutionSeesNothingAndItsIdentifiersLookLikeThoseThatDoNotExist() throws SQLException {
        World mine = world();
        World theirs = world();
        ScholarshipView created = scholarship(mine, "BECA-25", "25");
        AwardView award = requested(mine, created, 0, FEB, JUN);

        assertThatThrownBy(() -> manageScholarships().get(theirs.administrator(), request(), created.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> viewAwards().get(theirs.administrator(), request(), award.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> decideAward().approve(theirs.administrator(), request(), "x-1", award.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> requestAward().request(theirs.coordinator(), request(),
                new RequestAward.Command(created.id(), theirs.student(0), theirs.year(), FEB, JUN)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> manageScholarships().update(theirs.administrator(), request(), created.id(), 0,
                percent(theirs, "30"))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(manageScholarships().list(theirs.administrator(), request(), new ScholarshipFilter(null, null),
                PageRequest.of(0, 50, null, ScholarshipFilter.SORT_FIELDS, "code", true)).total()).isZero();
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_scholarship")).isZero();
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_award")).isZero();
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_award_decision")).isZero();
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_scholarship_concept")).isZero();
        assertThat(countVisibleAsApplication(mine.institution(), "scholarships_scholarship")).isEqualTo(1);
    }

    @Test
    void aScholarshipOfAnotherInstitutionCannotBeAwardedToAStudentOfThisOne() throws SQLException {
        World mine = world();
        World theirs = world();
        ScholarshipView foreign = scholarship(theirs, "BECA-25", "25");

        assertThatThrownBy(() -> requestAward().request(mine.coordinator(), request(),
                new RequestAward.Command(foreign.id(), mine.student(0), mine.year(), FEB, JUN)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
