package com.confia.scholarships.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.scholarships.ScholarshipsFixture;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.scholarships.domain.DiscountPendingApprovalException;
import com.confia.scholarships.domain.IdempotencyKeyRequiredException;
import com.confia.scholarships.domain.InvalidDiscountChangeStateException;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.scholarships.domain.SelfApprovalForbiddenException;
import com.confia.scholarships.domain.StaleVersionException;
import com.confia.scholarships.infrastructure.JooqDiscountsInForceRepository;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.SecurityContext;
import com.confia.support.StaffRoleFixtures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The approval of a campaign discount by another person against a real PostgreSQL (docs/cortes/scholarships-discounts.md, owner
 * decision of 2026-11-26, S7): a campaign discount is born pending and applies to nothing until a different person approves it;
 * widening it (a greater value, another type, an earlier start, a later end, a concept more, stackable turned on) and reactivating
 * it are proposals that change nothing until a different person approves them; narrowing and deactivating are immediate. The
 * decision names the version the person saw, carries an Idempotency-Key and, when it rejects, a reason that never reaches the audit
 * trail. The database refuses the same things whatever the code says. People, codes and amounts are fictional.
 */
class CampaignApprovalIT extends ScholarshipsFixture {

    private static final LocalDate FROM = LocalDate.parse("2027-01-01");
    private static final LocalDate TO = LocalDate.parse("2027-12-31");
    private static final LocalDate FEB = LocalDate.parse("2027-02-01");
    private static final String REJECTION = "El descuento propuesto excede el presupuesto aprobado";

    private record World(Env env, AuthenticatedActor creator, AuthenticatedActor approver, AuthenticatedActor otherApprover) {

        UUID institution() {
            return env.institution();
        }
    }

    private World world() throws SQLException {
        Env env = seed(1, "1500.00", "10", "HNL", Concepts.TWO_IN_ONE_PLAN);
        UUID institution = env.institution();
        return new World(env, actorOf(institution, "administrator"), actorOf(institution, "super_admin"),
                actorOf(institution, "super_admin"));
    }

    private ManageDiscounts.Draft draft(World w, String points, LocalDate from, LocalDate to, boolean stackable, UUID... concepts) {
        return new ManageDiscounts.Draft("Campana del " + points + " %", "PERCENTAGE", points, null, from, to, stackable,
                concepts.length == 0 ? Set.of(w.env().concept()) : Set.of(concepts));
    }

    private DiscountView created(World w, String code, String points) {
        return manageDiscounts().create(w.creator(), request(), code, "campaign", draft(w, points, FROM, TO, true));
    }

    private DiscountView approved(World w, DiscountView waiting) {
        return decideDiscountChange().approve(w.approver(), request(), "approve-" + UUID.randomUUID(), waiting.id(),
                waiting.version());
    }

    private DiscountView edited(World w, DiscountView before, ManageDiscounts.Draft draft) {
        return manageDiscounts().update(w.creator(), request(), before.id(), before.version(), draft);
    }

    private List<DiscountBenefits.Applicable> inForce(World w) {
        var context = new SecurityContext("", "system", w.institution().toString(), UUID.randomUUID().toString());
        return transactionRunner().execute(context,
                () -> new DiscountBenefitsService(new JooqDiscountsInForceRepository(dsl)).inForce(
                        new InstitutionId(w.institution()), w.env().students().get(0), w.env().concept(), FEB, CurrencyCode.HNL, false));
    }

    private long audit(World w, String action) throws SQLException {
        return scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = ?",
                w.institution(), action);
    }

    private long decisions(World w) throws SQLException {
        return scalar(w.institution(), "select count(*) from scholarships_discount_change_decision where institution_id = ?",
                w.institution());
    }

    // --- The creation ---

    @Test
    void aCampaignDiscountIsBornPendingAppliesToNothingAndIsAudited() throws SQLException {
        World w = world();

        DiscountView created = created(w, "CAMP-10", "10");

        assertThat(created.approvalStatus()).isEqualTo("pending");
        assertThat(created.createdBy()).isEqualTo(w.creator().accountId());
        assertThat(created.pendingChange()).isNull();
        assertThat(created.version()).isZero();
        assertThat(inForce(w)).as("a campaign discount that nobody else approved gives nothing").isEmpty();
        assertThat(audit(w, "scholarships.discount.created")).isEqualTo(1);
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = "
                + "'scholarships.discount.created' and after_value->>'approvalStatus' = 'pending'", w.institution())).isEqualTo(1);
    }

    @Test
    void anAgreementDiscountNeedsNoApproval() throws SQLException {
        World w = world();

        DiscountView agreement = manageDiscounts().create(w.creator(), request(), "CONV-10", "agreement",
                draft(w, "10", FROM, TO, true));

        assertThat(agreement.approvalStatus()).isEqualTo("approved");
        assertThat(agreement.pendingChange()).isNull();
    }

    @Test
    void whoCreatedItNeverApprovesNorRejectsItAndNothingChanges() throws SQLException {
        World w = world();
        DiscountView created = created(w, "CAMP-10", "10");

        assertThatThrownBy(() -> decideDiscountChange().approve(w.creator(), request(), "self-1", created.id(), created.version()))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> decideDiscountChange().reject(w.creator(), request(), "self-2", created.id(), created.version(),
                REJECTION)).isInstanceOf(SelfApprovalForbiddenException.class);

        assertThat(manageDiscounts().get(w.creator(), request(), created.id()).approvalStatus()).isEqualTo("pending");
        assertThat(decisions(w)).isZero();
        assertThat(audit(w, "scholarships.discount.approved")).isZero();
    }

    @Test
    void theDatabaseItselfRefusesADecisionByWhoCreatedItWhateverTheCodeSays() throws SQLException {
        World w = world();
        DiscountView created = created(w, "CAMP-10", "10");

        assertThatThrownBy(() -> ownerExec(w, w.creator().accountId(),
                "insert into scholarships_discount_change_decision (institution_id, id, discount_id, discount_version, decision, "
                        + "decided_by, request_key, request_hash) values (?, ?, ?, 0, 'approved', ?, 'k-1', ?)",
                w.institution(), UUID.randomUUID(), created.id(), w.creator().accountId(), "a".repeat(64)))
                .isInstanceOf(SQLException.class).hasMessageContaining("never approves or rejects");
        assertThat(decisions(w)).isZero();
    }

    @Test
    void anotherPersonApprovesAndFromThenOnItAppliesAndTheDecisionIsAuditedWithTheApprover() throws SQLException {
        World w = world();
        DiscountView created = created(w, "CAMP-10", "10");

        DiscountView approved = decideDiscountChange().approve(w.approver(), request(), "approve-1", created.id(), 0);

        assertThat(approved.approvalStatus()).isEqualTo("approved");
        assertThat(approved.version()).isEqualTo(1);
        assertThat(inForce(w)).singleElement().satisfies(found -> assertThat(found.discountCode()).isEqualTo("CAMP-10"));
        assertThat(decisions(w)).isEqualTo(1);
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = "
                + "'scholarships.discount.approved' and approver_id = ? and actor_id = ?", w.institution(),
                w.approver().accountId(), w.approver().accountId())).isEqualTo(1);
    }

    @Test
    void theDecisionNamesTheVersionTheDeciderSaw() throws SQLException {
        World w = world();
        DiscountView created = created(w, "CAMP-10", "10");

        assertThatThrownBy(() -> decideDiscountChange().approve(w.approver(), request(), "stale-1", created.id(), 7))
                .isInstanceOf(StaleVersionException.class);
        assertThat(inForce(w)).isEmpty();
        assertThat(decisions(w)).isZero();
    }

    @Test
    void aSecondDecisionOnWhatWasAlreadyDecidedIsAStateError() throws SQLException {
        World w = world();
        DiscountView done = approved(w, created(w, "CAMP-10", "10"));

        assertThatThrownBy(() -> decideDiscountChange().approve(w.otherApprover(), request(), "again-1", done.id(), done.version()))
                .isInstanceOf(InvalidDiscountChangeStateException.class);
        assertThatThrownBy(() -> decideDiscountChange().reject(w.otherApprover(), request(), "again-2", done.id(), done.version(),
                REJECTION)).isInstanceOf(InvalidDiscountChangeStateException.class);
    }

    @Test
    void aRejectionKeepsItsReasonOnlyInTheDecisionAndTheCreationNeverApplies() throws SQLException {
        World w = world();
        DiscountView created = created(w, "CAMP-10", "10");

        DiscountView rejected = decideDiscountChange().reject(w.approver(), request(), "reject-1", created.id(), 0, REJECTION);

        assertThat(rejected.approvalStatus()).isEqualTo("rejected");
        assertThat(inForce(w)).isEmpty();
        assertThat(scalar(w.institution(), "select count(*) from scholarships_discount_change_decision where institution_id = ? "
                + "and decision = 'rejected' and reason = ?", w.institution(), REJECTION)).isEqualTo(1);
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = "
                + "'scholarships.discount.rejected' and reason = ? and approver_id is null and after_value::text not like ?",
                w.institution(), sha256(REJECTION), "%presupuesto%")).isEqualTo(1);
        assertThatThrownBy(() -> edited(w, rejected, draft(w, "5", FROM, TO, true))).isInstanceOf(DiscountPendingApprovalException.class);
        assertThatThrownBy(() -> manageDiscounts().deactivate(w.creator(), request(), rejected.id()))
                .isInstanceOf(DiscountPendingApprovalException.class);
    }

    @Test
    void aReasonOutOfItsBoundsIsRefusedBeforeAnythingIsWritten() throws SQLException {
        World w = world();
        DiscountView created = created(w, "CAMP-10", "10");

        assertThatThrownBy(() -> decideDiscountChange().reject(w.approver(), request(), "r-1", created.id(), 0, "corto"))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThat(decisions(w)).isZero();
    }

    @Test
    void whileItWaitsForItsFirstApprovalItIsNotEditedNorDeactivated() throws SQLException {
        World w = world();
        DiscountView created = created(w, "CAMP-10", "10");

        assertThatThrownBy(() -> edited(w, created, draft(w, "5", FROM, TO, true))).isInstanceOf(DiscountPendingApprovalException.class);
        assertThatThrownBy(() -> manageDiscounts().deactivate(w.creator(), request(), created.id()))
                .isInstanceOf(DiscountPendingApprovalException.class);
    }

    // --- Keys ---

    @Test
    void theSameKeyAndTheSameRequestAnswerTheSameDecisionOnce() throws SQLException {
        World w = world();
        DiscountView created = created(w, "CAMP-10", "10");

        DiscountView first = decideDiscountChange().approve(w.approver(), request(), "same-key", created.id(), 0);
        DiscountView again = decideDiscountChange().approve(w.approver(), request(), "same-key", created.id(), 0);

        assertThat(again.version()).isEqualTo(first.version());
        assertThat(again.approvalStatus()).isEqualTo("approved");
        assertThat(decisions(w)).isEqualTo(1);
        assertThat(audit(w, "scholarships.discount.approved")).isEqualTo(1);
    }

    @Test
    void theSameKeyForAnotherRequestOrAnotherPersonIsAMismatchAndAMissingKeyIsRefused() throws SQLException {
        World w = world();
        DiscountView created = created(w, "CAMP-10", "10");
        DiscountView other = created(w, "CAMP-20", "20");
        decideDiscountChange().approve(w.approver(), request(), "k-1", created.id(), 0);

        assertThatThrownBy(() -> decideDiscountChange().approve(w.approver(), request(), "k-1", other.id(), 0))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> decideDiscountChange().approve(w.otherApprover(), request(), "k-1", created.id(), 0))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> decideDiscountChange().approve(w.approver(), request(), null, other.id(), 0))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThat(decisions(w)).isEqualTo(1);
    }

    // --- Widening and reactivating ---

    @Test
    void wideningAnApprovedCampaignDiscountLeavesAProposalAndTheTermsInForceKeepApplying() throws SQLException {
        World w = world();
        DiscountView settled = approved(w, created(w, "CAMP-10", "10"));

        DiscountView proposed = edited(w, settled, draft(w, "25", FROM, TO, true));

        assertThat(proposed.percent()).as("the terms in force").isEqualTo("10.00");
        assertThat(proposed.pendingChange().percent()).isEqualTo("25.00");
        assertThat(proposed.pendingChange().requestedBy()).isEqualTo(w.creator().accountId());
        assertThat(proposed.pendingChange().reactivate()).isFalse();
        assertThat(proposed.version()).isEqualTo(settled.version() + 1);
        assertThat(inForce(w)).singleElement().satisfies(found -> assertThat(found.benefit().apply(com.confia.kernel.Money.of("1000.00",
                CurrencyCode.HNL)).amount()).isEqualByComparingTo("100.00"));
        assertThat(audit(w, "scholarships.discount.change_requested")).isEqualTo(1);
        assertThat(audit(w, "scholarships.discount.updated")).isZero();
    }

    @Test
    void anotherPersonApprovesTheProposalAndItBecomesTheTermsInForce() throws SQLException {
        World w = world();
        DiscountView settled = approved(w, created(w, "CAMP-10", "10"));
        UUID second = secondConcept(w.env());
        DiscountView proposed = edited(w, settled, draft(w, "25", LocalDate.parse("2026-12-01"), TO, false, w.env().concept(), second));

        DiscountView done = decideDiscountChange().approve(w.approver(), request(), "approve-2", proposed.id(), proposed.version());

        assertThat(done.pendingChange()).isNull();
        assertThat(done.percent()).isEqualTo("25.00");
        assertThat(done.validFrom()).isEqualTo(LocalDate.parse("2026-12-01"));
        assertThat(done.stackable()).isFalse();
        assertThat(done.concepts()).hasSize(2);
        assertThat(inForce(w)).singleElement().satisfies(found -> assertThat(found.benefit().apply(com.confia.kernel.Money.of("1000.00",
                CurrencyCode.HNL)).amount()).isEqualByComparingTo("250.00"));
        assertThat(audit(w, "scholarships.discount.approved")).isEqualTo(2);
    }

    @Test
    void whoProposedNeverApprovesItWhileAnotherPersonMay() throws SQLException {
        World w = world();
        DiscountView settled = approved(w, created(w, "CAMP-10", "10"));
        // The person who approved the creation proposes now: the segregation is per proposal, not per discount.
        DiscountView proposed = manageDiscounts().update(w.approver(), request(), settled.id(), settled.version(),
                draft(w, "25", FROM, TO, true));

        assertThatThrownBy(() -> decideDiscountChange().approve(w.approver(), request(), "self-3", proposed.id(), proposed.version()))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> decideDiscountChange().reject(w.approver(), request(), "self-4", proposed.id(), proposed.version(),
                REJECTION)).isInstanceOf(SelfApprovalForbiddenException.class);
        assertThat(manageDiscounts().get(w.approver(), request(), proposed.id()).pendingChange()).isNotNull();
        assertThat(decideDiscountChange().approve(w.creator(), request(), "other-1", proposed.id(), proposed.version()).percent())
                .isEqualTo("25.00");
    }

    @Test
    void aRejectionOfAProposalKeepsTheTermsInForce() throws SQLException {
        World w = world();
        DiscountView settled = approved(w, created(w, "CAMP-10", "10"));
        DiscountView proposed = edited(w, settled, draft(w, "25", FROM, TO, true));

        DiscountView dropped = decideDiscountChange().reject(w.approver(), request(), "reject-2", proposed.id(), proposed.version(),
                REJECTION);

        assertThat(dropped.pendingChange()).isNull();
        assertThat(dropped.percent()).isEqualTo("10.00");
        assertThat(dropped.approvalStatus()).isEqualTo("approved");
        assertThat(inForce(w)).hasSize(1);
        assertThat(audit(w, "scholarships.discount.rejected")).isEqualTo(1);
    }

    @Test
    void everyKindOfWideningWaitsAndEveryNarrowingIsImmediate() throws SQLException {
        World w = world();
        UUID second = secondConcept(w.env());
        DiscountView settled = approved(w, manageDiscounts().create(w.creator(), request(), "CAMP-10", "campaign",
                draft(w, "10", FROM, TO, false)));

        // Each of these waits (the proposal is dropped by its rejection before the next one).
        assertWaits(w, settled, draft(w, "10.01", FROM, TO, false));
        settled = rejectedProposalOf(w, settled);
        assertWaits(w, settled, draft(w, "10", FROM.minusDays(1), TO, false));
        settled = rejectedProposalOf(w, settled);
        assertWaits(w, settled, draft(w, "10", FROM, TO.plusDays(1), false));
        settled = rejectedProposalOf(w, settled);
        assertWaits(w, settled, draft(w, "10", FROM, TO, false, w.env().concept(), second));
        settled = rejectedProposalOf(w, settled);
        assertWaits(w, settled, draft(w, "10", FROM, TO, true));
        settled = rejectedProposalOf(w, settled);
        assertWaits(w, settled, new ManageDiscounts.Draft("Fijo", "FIXED_AMOUNT", null, "1.00", FROM, TO, false,
                Set.of(w.env().concept())));
        settled = rejectedProposalOf(w, settled);

        // These do not.
        DiscountView smaller = edited(w, settled, draft(w, "9.99", FROM.plusDays(1), TO.minusDays(1), false));
        assertThat(smaller.pendingChange()).isNull();
        assertThat(smaller.percent()).isEqualTo("9.99");
        assertThat(smaller.validFrom()).isEqualTo(FROM.plusDays(1));
        assertThat(manageDiscounts().deactivate(w.creator(), request(), smaller.id()).active()).isFalse();
    }

    private void assertWaits(World w, DiscountView settled, ManageDiscounts.Draft draft) {
        DiscountView proposed = edited(w, settled, draft);
        assertThat(proposed.pendingChange()).as(draft.toString()).isNotNull();
        assertThat(proposed.percent()).as("the terms in force stay").isEqualTo(settled.percent());
        assertThat(proposed.validFrom()).isEqualTo(settled.validFrom());
    }

    private DiscountView rejectedProposalOf(World w, DiscountView settled) {
        DiscountView waiting = manageDiscounts().get(w.creator(), request(), settled.id());
        return decideDiscountChange().reject(w.approver(), request(), "drop-" + UUID.randomUUID(), waiting.id(), waiting.version(),
                REJECTION);
    }

    @Test
    void aSecondProposalWhileOneWaitsIsRefusedButTheDiscountCanBeDeactivated() throws SQLException {
        World w = world();
        DiscountView proposed = edited(w, approved(w, created(w, "CAMP-10", "10")), draft(w, "25", FROM, TO, true));

        assertThatThrownBy(() -> edited(w, proposed, draft(w, "30", FROM, TO, true))).isInstanceOf(DiscountPendingApprovalException.class);
        DiscountView off = manageDiscounts().deactivate(w.creator(), request(), proposed.id());
        assertThat(off.active()).isFalse();
        assertThat(off.pendingChange()).isNotNull();
        assertThat(inForce(w)).isEmpty();
        assertThatThrownBy(() -> manageDiscounts().reactivate(w.creator(), request(), proposed.id()))
                .isInstanceOf(DiscountPendingApprovalException.class);
    }

    @Test
    void reactivatingACampaignDiscountIsAProposalAndOnlyItsApprovalTurnsItOn() throws SQLException {
        World w = world();
        DiscountView settled = approved(w, created(w, "CAMP-10", "10"));
        DiscountView off = manageDiscounts().deactivate(w.creator(), request(), settled.id());
        assertThat(off.pendingChange()).as("deactivating is immediate").isNull();

        DiscountView asked = manageDiscounts().reactivate(w.creator(), request(), off.id());

        assertThat(asked.active()).isFalse();
        assertThat(asked.pendingChange().reactivate()).isTrue();
        assertThat(inForce(w)).isEmpty();
        assertThat(audit(w, "scholarships.discount.change_requested")).isEqualTo(1);
        assertThat(audit(w, "scholarships.discount.reactivated")).isZero();

        DiscountView on = decideDiscountChange().approve(w.approver(), request(), "approve-3", asked.id(), asked.version());
        assertThat(on.active()).isTrue();
        assertThat(on.pendingChange()).isNull();
        assertThat(inForce(w)).hasSize(1);
    }

    // --- What the database refuses whatever the code says ---

    @Test
    void theDatabaseRefusesToWidenOrReactivateAnApprovedCampaignDiscountWithoutTheDecisionOfAnotherPerson() throws SQLException {
        World w = world();
        DiscountView settled = approved(w, created(w, "CAMP-10", "10"));
        UUID actor = w.creator().accountId();
        String where = " where institution_id = ? and id = ?";

        for (String change : List.of("percent = 11", "valid_from = valid_from - 1", "valid_to = valid_to + 1",
                "benefit_type = 'FIXED_AMOUNT', percent = null, fixed_amount = 5")) {
            assertThatThrownBy(() -> ownerExec(w, actor, "update scholarships_discount set " + change + where, w.institution(),
                    settled.id())).as(change).isInstanceOf(SQLException.class).hasMessageContaining("needs the approval");
        }
        DiscountView off = manageDiscounts().deactivate(w.creator(), request(), settled.id());
        assertThatThrownBy(() -> ownerExec(w, actor, "update scholarships_discount set is_active = true" + where, w.institution(),
                off.id())).isInstanceOf(SQLException.class).hasMessageContaining("needs the approval");
        // Narrowing is allowed.
        ownerExec(w, actor, "update scholarships_discount set percent = 9, version = version + 1" + where, w.institution(), off.id());
    }

    @Test
    void theDatabaseRefusesToApproveACreationWithoutADecisionAndToBornACampaignDiscountApproved() throws SQLException {
        World w = world();
        DiscountView created = created(w, "CAMP-10", "10");

        assertThatThrownBy(() -> ownerExec(w, w.approver().accountId(),
                "update scholarships_discount set approval_status = 'approved' where institution_id = ? and id = ?",
                w.institution(), created.id())).isInstanceOf(SQLException.class).hasMessageContaining("only by the decision");
        assertThatThrownBy(() -> ownerExec(w, w.creator().accountId(),
                "insert into scholarships_discount (institution_id, id, code, name, kind, benefit_type, percent, valid_from, "
                        + "valid_to, is_stackable, created_by, approval_status) values (?, ?, 'LISTO', 'N', 'campaign', "
                        + "'PERCENTAGE', 10, date '2027-01-01', date '2027-12-31', true, ?, 'approved')",
                w.institution(), UUID.randomUUID(), w.creator().accountId())).isInstanceOf(SQLException.class)
                .hasMessageContaining("born pending");
        assertThatThrownBy(() -> ownerExec(w, w.creator().accountId(),
                "insert into scholarships_discount (institution_id, id, code, name, kind, benefit_type, percent, valid_from, "
                        + "valid_to, is_stackable, created_by, approval_status) values (?, ?, 'CONV', 'N', 'agreement', "
                        + "'PERCENTAGE', 10, date '2027-01-01', date '2027-12-31', true, ?, 'pending')",
                w.institution(), UUID.randomUUID(), w.creator().accountId())).isInstanceOf(SQLException.class);
    }

    @Test
    void theDatabaseRefusesAProposalInTheNameOfSomebodyElseAndAConceptAddedOutsideAnApproval() throws SQLException {
        World w = world();
        DiscountView settled = approved(w, created(w, "CAMP-10", "10"));
        UUID second = secondConcept(w.env());

        assertThatThrownBy(() -> ownerExec(w, w.creator().accountId(),
                "update scholarships_discount set pending_requested_by = ?, pending_requested_at = now(), pending_name = 'N', "
                        + "pending_benefit_type = 'PERCENTAGE', pending_percent = 50, pending_valid_from = valid_from, "
                        + "pending_valid_to = valid_to, pending_is_stackable = true, pending_reactivate = false, "
                        + "pending_concepts = array[?]::uuid[], version = version + 1 where institution_id = ? and id = ?",
                w.approver().accountId(), w.env().concept(), w.institution(), settled.id()))
                .isInstanceOf(SQLException.class).hasMessageContaining("person of the session");
        assertThatThrownBy(() -> ownerExec(w, w.creator().accountId(),
                "insert into scholarships_discount_concept (institution_id, discount_id, payment_concept_id) values (?, ?, ?)",
                w.institution(), settled.id(), second)).isInstanceOf(SQLException.class)
                .hasMessageContaining("only when it is born or by the approval");
    }

    @Test
    void aPendingOrRejectedDiscountDoesNotChangeAndTheConceptsOfAPendingOneAreNotAddedAfterItsBirth() throws SQLException {
        World w = world();
        DiscountView waiting = created(w, "CAMP-10", "10");
        UUID second = secondConcept(w.env());

        assertThatThrownBy(() -> ownerExec(w, w.creator().accountId(),
                "update scholarships_discount set percent = 99 where institution_id = ? and id = ?", w.institution(), waiting.id()))
                .isInstanceOf(SQLException.class).hasMessageContaining("does not change");
        assertThatThrownBy(() -> ownerExec(w, w.creator().accountId(),
                "insert into scholarships_discount_concept (institution_id, discount_id, payment_concept_id) values (?, ?, ?)",
                w.institution(), waiting.id(), second)).isInstanceOf(SQLException.class);
    }

    // --- Isolation ---

    @Test
    void anotherInstitutionNeitherSeesNorDecidesTheDiscountsOfThisOne() throws SQLException {
        World mine = world();
        World theirs = world();
        DiscountView created = created(mine, "CAMP-10", "10");
        approved(mine, created(mine, "CAMP-20", "20"));

        assertThatThrownBy(() -> decideDiscountChange().approve(theirs.approver(), request(), "x-1", created.id(), 0))
                .isInstanceOf(ResourceNotFoundException.class);
        // There is one decision, so a count of zero is the work of the row-level security and not an empty table.
        assertThat(countVisibleAsApplication(mine.institution(), "scholarships_discount_change_decision")).isEqualTo(1);
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_discount_change_decision")).isZero();
    }

    @Test
    void withoutAnInstitutionInTheSessionNothingIsVisibleOrWritableOnTheNewTable() throws SQLException {
        World w = world();
        approved(w, created(w, "CAMP-10", "10"));

        assertThat(countWithSetting(null, "scholarships_discount_change_decision")).as("setting never made").isZero();
        assertThat(countWithSetting("", "scholarships_discount_change_decision")).as("empty setting").isZero();
        assertThat(countWithSetting("", "scholarships_discount")).isZero();
        assertThat(countWithSetting(null, "scholarships_discount")).isZero();
        assertThat(countWithSetting("", "scholarships_discount_assignment")).isZero();
        assertThat(countWithSetting("", "scholarships_discount_in_use")).isZero();
    }

    // --- Helpers that read and write as the database sees them ---

    private static void ownerExec(World w, UUID actor, String sql, Object... parameters) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(w.institution())) {
            try (PreparedStatement context = c.prepareStatement("select set_config('app.actor_id', ?, true)")) {
                context.setString(1, actor.toString());
                context.execute();
            }
            try (PreparedStatement s = c.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    s.setObject(i + 1, parameters[i]);
                }
                s.executeUpdate();
            }
            c.commit();
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
