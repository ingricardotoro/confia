package com.confia.scholarships.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.scholarships.ScholarshipsFixture;
import com.confia.scholarships.domain.DiscountAssignmentOverlapException;
import com.confia.scholarships.domain.DiscountBenefitLockedException;
import com.confia.scholarships.domain.DiscountScopeLockedException;
import com.confia.scholarships.domain.DiscountTermsChangedException;
import com.confia.scholarships.domain.DuplicateDiscountCodeException;
import com.confia.scholarships.domain.IdempotencyKeyRequiredException;
import com.confia.scholarships.domain.InactiveReferenceException;
import com.confia.scholarships.domain.InvalidAssignmentStateException;
import com.confia.scholarships.domain.InvalidScholarshipsValueException;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.scholarships.domain.SelfApprovalForbiddenException;
import com.confia.scholarships.domain.StaleVersionException;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * The discounts and their assignments against a real PostgreSQL (docs/cortes/scholarships-discounts.md): the definition and the edit
 * of a discount, the narrowing once an assignment was approved, the request of an assignment of an agreement discount, the approval
 * by another person (segregation of duties), the rejection and the revocation with their reasons, the two approved assignments of one
 * discount for one student that never overlap, the keys of the decisions, two decisions at the same time, the audit entries and the
 * isolation between institutions. People, codes and amounts are fictional.
 */
class DiscountsIT extends ScholarshipsFixture {

    private static final LocalDate FROM = LocalDate.parse("2027-01-01");
    private static final LocalDate TO = LocalDate.parse("2027-12-31");
    private static final LocalDate FEB = LocalDate.parse("2027-02-01");
    private static final LocalDate JUN = LocalDate.parse("2027-06-30");

    private record World(Env env, AuthenticatedActor coordinator, AuthenticatedActor administrator,
            AuthenticatedActor otherAdministrator, AuthenticatedActor cashier) {

        UUID institution() {
            return env.institution();
        }

        UUID student(int index) {
            return env.students().get(index);
        }
    }

    private World world() throws SQLException {
        return worldOf(seed(2));
    }

    private World twoConceptsWorld() throws SQLException {
        return worldOf(seed(2, "1500.00", "10", "HNL", Concepts.TWO_IN_ONE_PLAN));
    }

    private World worldOf(Env env) throws SQLException {
        UUID institution = env.institution();
        return new World(env, actorOf(institution, "academic_coordinator"), actorOf(institution, "administrator"),
                actorOf(institution, "super_admin"), actorOf(institution, "cashier"));
    }

    private ManageDiscounts.Draft draft(World w, String points, boolean stackable) {
        return new ManageDiscounts.Draft("Descuento del " + points + " %", "PERCENTAGE", points, null, FROM, TO, stackable,
                Set.of(w.env().concept()));
    }

    private DiscountView agreement(World w, String code, String points) {
        return manageDiscounts().create(w.administrator(), request(), code, "agreement", draft(w, points, true));
    }

    /** A campaign discount that applies: created by the administrator and approved by another person. */
    private DiscountView campaign(World w, String code, String points) {
        return approvedByAnother(w.institution(), pendingCampaign(w, code, points));
    }

    /** A campaign discount that waits for the approval of another person. */
    private DiscountView pendingCampaign(World w, String code, String points) {
        return manageDiscounts().create(w.administrator(), request(), code, "campaign", draft(w, points, true));
    }

    private AssignmentView requested(World w, DiscountView discount, int student, LocalDate from, LocalDate to) {
        return requestAssignment().request(w.coordinator(), request(),
                new RequestDiscountAssignment.Command(discount.id(), w.student(student), from, to));
    }

    private AssignmentView approved(World w, AssignmentView assignment) {
        return decideAssignment().approve(w.administrator(), request(), "approve-" + UUID.randomUUID(), assignment.id());
    }

    private long auditEntries(World w, String action) throws SQLException {
        return scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = ?",
                w.institution(), action);
    }

    private static PageRequest sortedByCode() {
        return PageRequest.of(0, 50, null, DiscountFilter.SORT_FIELDS, "code", true);
    }

    // --- Discounts ---

    @Test
    void aDiscountIsCreatedWithItsBenefitValidityAndConceptsAndAudited() throws SQLException {
        World w = world();

        DiscountView created = manageDiscounts().create(w.administrator(), request(), "CAMP-10", "campaign",
                new ManageDiscounts.Draft("  Campana de matricula  ", "PERCENTAGE", "10", null, FROM, TO, true,
                        Set.of(w.env().concept())));

        assertThat(created.code()).isEqualTo("CAMP-10");
        assertThat(created.name()).isEqualTo("Campana de matricula");
        assertThat(created.kind()).isEqualTo("campaign");
        assertThat(created.benefitType()).isEqualTo("PERCENTAGE");
        assertThat(created.percent()).isEqualTo("10.00");
        assertThat(created.fixedAmount()).isNull();
        assertThat(created.validFrom()).isEqualTo(FROM);
        assertThat(created.validTo()).isEqualTo(TO);
        assertThat(created.stackable()).isTrue();
        assertThat(created.concepts()).extracting(DiscountView.ConceptView::code).containsExactly("COL");
        assertThat(created.active()).isTrue();
        assertThat(created.version()).isZero();
        assertThat(created.inUse()).isFalse();
        assertThat(auditEntries(w, "scholarships.discount.created")).isEqualTo(1);
        assertThat(manageDiscounts().get(w.cashier(), request(), created.id()).code()).isEqualTo("CAMP-10");
    }

    @Test
    void aFixedAmountDiscountKeepsItsAmountAndHasNoPercentage() throws SQLException {
        World w = world();

        DiscountView created = manageDiscounts().create(w.administrator(), request(), "FIJO-100", "agreement",
                new ManageDiscounts.Draft("Convenio fijo", "FIXED_AMOUNT", null, "100.00", FROM, TO, false,
                        Set.of(w.env().concept())));

        assertThat(created.percent()).isNull();
        assertThat(created.fixedAmount().amount().toPlainString()).isEqualTo("100.0000");
        assertThat(created.stackable()).isFalse();
    }

    @Test
    void aCodeIsUniqueInTheInstitutionWhateverItsCapitals() throws SQLException {
        World w = world();
        campaign(w, "CAMP-10", "10");

        assertThatThrownBy(() -> campaign(w, "camp-10", "20")).isInstanceOf(DuplicateDiscountCodeException.class);
        assertThat(auditEntries(w, "scholarships.discount.created")).isEqualTo(1);
    }

    @Test
    void aValueOutOfItsRuleOrAConceptThatDoesNotExistIsRefused() throws SQLException {
        World w = world();
        Set<UUID> concepts = Set.of(w.env().concept());

        assertThatThrownBy(() -> manageDiscounts().create(w.administrator(), request(), "X-1", "campaign",
                new ManageDiscounts.Draft("N", "PERCENTAGE", "0", null, FROM, TO, true, concepts)))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThatThrownBy(() -> manageDiscounts().create(w.administrator(), request(), "X-2", "campaign",
                new ManageDiscounts.Draft("N", "PERCENTAGE", "100.01", null, FROM, TO, true, concepts)))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThatThrownBy(() -> manageDiscounts().create(w.administrator(), request(), "X-3", "campaign",
                new ManageDiscounts.Draft("N", "FIXED_AMOUNT", null, "0.00", FROM, TO, true, concepts)))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThatThrownBy(() -> manageDiscounts().create(w.administrator(), request(), "X-4", "campaign",
                new ManageDiscounts.Draft("N", "PERCENTAGE", "10", null, TO, FROM, true, concepts)))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThatThrownBy(() -> manageDiscounts().create(w.administrator(), request(), "X-5", "other",
                new ManageDiscounts.Draft("N", "PERCENTAGE", "10", null, FROM, TO, true, concepts)))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThatThrownBy(() -> manageDiscounts().create(w.administrator(), request(), "X-6", "campaign",
                new ManageDiscounts.Draft("N", "PERCENTAGE", "10", null, FROM, TO, true, Set.of(UUID.randomUUID()))))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThat(manageDiscounts().list(w.cashier(), request(), new DiscountFilter(null, null, null), sortedByCode()).total())
                .isZero();
    }

    @Test
    void anEditNeedsTheVersionAndAdvancesIt() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");

        DiscountView edited = manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("Nuevo nombre", "PERCENTAGE", "15", null, FEB, JUN, false, Set.of(w.env().concept())));

        assertThat(edited.version()).isEqualTo(1);
        assertThat(edited.name()).isEqualTo("Nuevo nombre");
        assertThat(edited.percent()).isEqualTo("15.00");
        assertThat(edited.validFrom()).isEqualTo(FEB);
        assertThat(edited.stackable()).isFalse();
        assertThat(edited.code()).isEqualTo("CONV-10");
        assertThat(auditEntries(w, "scholarships.discount.updated")).isEqualTo(1);
        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), created.id(), 0, draft(w, "20", true)))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void onceAnAssignmentWasApprovedTheBenefitAndTheStackableFlagAreLockedButTheNameChanges() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");
        approved(w, requested(w, created, 0, FEB, JUN));

        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), created.id(), 0, draft(w, "11", true)))
                .isInstanceOf(DiscountBenefitLockedException.class);
        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("N", "FIXED_AMOUNT", null, "10.00", FROM, TO, true, Set.of(w.env().concept()))))
                .isInstanceOf(DiscountBenefitLockedException.class);
        // S8: the stackable flag is locked like the type and the value (docs/cortes/scholarships-discounts.md).
        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("Otro nombre", "PERCENTAGE", "10", null, FROM, TO, false, Set.of(w.env().concept()))))
                .isInstanceOf(DiscountBenefitLockedException.class);
        assertThat(manageDiscounts().get(w.administrator(), request(), created.id()).stackable()).isTrue();
        DiscountView edited = manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("Otro nombre", "PERCENTAGE", "10", null, FROM, TO, true, Set.of(w.env().concept())));
        assertThat(edited.name()).isEqualTo("Otro nombre");
        assertThat(edited.stackable()).isTrue();
        assertThat(edited.inUse()).isTrue();
    }

    @Test
    void theBenefitStaysLockedWhenTheOnlyApprovedAssignmentWasRevoked() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");
        AssignmentView assignment = approved(w, requested(w, created, 0, FEB, JUN));
        decideAssignment().revoke(w.administrator(), request(), "revoke-1", assignment.id(), REASON);

        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), created.id(), 0, draft(w, "11", true)))
                .isInstanceOf(DiscountBenefitLockedException.class);
    }

    @Test
    void onceAnAssignmentWasApprovedTheValidityOnlyShortens() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");
        approved(w, requested(w, created, 0, FEB, JUN));
        Set<UUID> concepts = Set.of(w.env().concept());

        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("N", "PERCENTAGE", "10", null, FROM.minusDays(1), TO, true, concepts)))
                .isInstanceOf(DiscountScopeLockedException.class);
        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("N", "PERCENTAGE", "10", null, FROM, TO.plusDays(1), true, concepts)))
                .isInstanceOf(DiscountScopeLockedException.class);
        DiscountView shorter = manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("N", "PERCENTAGE", "10", null, FEB, JUN, true, concepts));
        assertThat(shorter.validFrom()).isEqualTo(FEB);
        assertThat(shorter.validTo()).isEqualTo(JUN);
    }

    @Test
    void onceAnAssignmentWasApprovedConceptsAreOnlyRemovedNeverAdded() throws SQLException {
        World w = twoConceptsWorld();
        UUID second = secondConcept(w.env());
        DiscountView created = agreement(w, "CONV-10", "10");
        approved(w, requested(w, created, 0, FEB, JUN));

        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("N", "PERCENTAGE", "10", null, FROM, TO, true, Set.of(w.env().concept(), second))))
                .isInstanceOf(DiscountScopeLockedException.class);
    }

    @Test
    void withoutApprovedAssignmentsTheBenefitTheValidityAndTheConceptsChangeFreely() throws SQLException {
        World w = twoConceptsWorld();
        UUID second = secondConcept(w.env());
        DiscountView created = agreement(w, "CONV-10", "10");
        requested(w, created, 0, FEB, JUN);

        DiscountView wider = manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("N", "FIXED_AMOUNT", null, "50.00", LocalDate.parse("2026-12-01"),
                        LocalDate.parse("2028-01-31"), true, Set.of(w.env().concept(), second)));

        assertThat(wider.benefitType()).isEqualTo("FIXED_AMOUNT");
        assertThat(wider.concepts()).hasSize(2);
        assertThat(wider.inUse()).isFalse();
    }

    @Test
    void aValidityThatLeavesAPendingOrApprovedAssignmentOutsideIsRefused() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");
        AssignmentView pending = requested(w, created, 0, FEB, JUN);
        Set<UUID> concepts = Set.of(w.env().concept());

        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("N", "PERCENTAGE", "10", null, FROM, LocalDate.parse("2027-05-31"), true, concepts)))
                .isInstanceOf(RejectedScholarshipFieldException.class)
                .extracting(e -> ((RejectedScholarshipFieldException) e).field()).isEqualTo("validTo");
        approved(w, pending);
        assertThatThrownBy(() -> manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("N", "PERCENTAGE", "10", null, LocalDate.parse("2027-03-01"), TO, true, concepts)))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        // The exact validity of the assignment is still allowed.
        assertThat(manageDiscounts().update(w.administrator(), request(), created.id(), 0,
                new ManageDiscounts.Draft("N", "PERCENTAGE", "10", null, FEB, JUN, true, concepts)).version()).isEqualTo(1);
    }

    @Test
    void theDatabaseItselfRefusesToChangeADiscountInUse() throws SQLException {
        World w = twoConceptsWorld();
        UUID second = secondConcept(w.env());
        DiscountView created = agreement(w, "CONV-10", "10");
        approved(w, requested(w, created, 0, FEB, JUN));

        assertThatThrownBy(() -> execAsOwner(w, "update scholarships_discount set percent = 11 where institution_id = ? and id = ?",
                w.institution(), created.id())).isInstanceOf(SQLException.class).hasMessageContaining("never changes");
        assertThatThrownBy(() -> execAsOwner(w, "update scholarships_discount set valid_to = valid_to + 1 "
                + "where institution_id = ? and id = ?", w.institution(), created.id()))
                .isInstanceOf(SQLException.class).hasMessageContaining("only shortens");
        assertThatThrownBy(() -> execAsOwner(w, "update scholarships_discount set valid_from = valid_from - 1 "
                + "where institution_id = ? and id = ?", w.institution(), created.id()))
                .isInstanceOf(SQLException.class).hasMessageContaining("only shortens");
        assertThatThrownBy(() -> execAsOwner(w, "insert into scholarships_discount_concept (institution_id, discount_id, "
                + "payment_concept_id) values (?, ?, ?)", w.institution(), created.id(), second))
                .isInstanceOf(SQLException.class).hasMessageContaining("no new concept");
        assertThatThrownBy(() -> execAsOwner(w, "update scholarships_discount set kind = 'campaign' "
                + "where institution_id = ? and id = ?", w.institution(), created.id()))
                .isInstanceOf(SQLException.class).hasMessageContaining("never change");
        assertThatThrownBy(() -> execAsOwner(w, "delete from scholarships_discount where institution_id = ? and id = ?",
                w.institution(), created.id())).isInstanceOf(SQLException.class);
    }

    /** One statement as the owner, with the person of the session set as the application does for every use case. */
    private static void execAsActor(World w, UUID actor, String sql, Object... parameters) throws SQLException {
        try (Connection c = com.confia.support.StaffRoleFixtures.ownerTransaction(w.institution())) {
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

    private static void execAsOwner(World w, String sql, Object... parameters) throws SQLException {
        try (Connection c = com.confia.support.StaffRoleFixtures.ownerTransaction(w.institution());
                PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
            c.commit();
        }
    }

    @Test
    void theDatabaseRefusesABenefitThatIsNotExactlyOneOfTheTwoAndAWindowOutOfRange() throws SQLException {
        World w = world();
        String insert = "insert into scholarships_discount (institution_id, id, code, name, kind, benefit_type, percent, "
                + "fixed_amount, valid_from, valid_to, is_stackable, created_by, approval_status) values (?, ?, ?, 'N', 'campaign', "
                + "?, ?, ?, ?::date, ?::date, true, ?, 'pending')";
        UUID by = UUID.randomUUID();

        assertThatThrownBy(() -> execAsOwner(w, insert, w.institution(), UUID.randomUUID(), "D1", "PERCENTAGE", 0, null,
                "2027-01-01", "2027-12-31", by)).isInstanceOf(SQLException.class).hasMessageContaining("benefit_chk");
        assertThatThrownBy(() -> execAsOwner(w, insert, w.institution(), UUID.randomUUID(), "D2", "PERCENTAGE", 100.01, null,
                "2027-01-01", "2027-12-31", by)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execAsOwner(w, insert, w.institution(), UUID.randomUUID(), "D3", "PERCENTAGE", 10, 5,
                "2027-01-01", "2027-12-31", by)).isInstanceOf(SQLException.class).hasMessageContaining("benefit_chk");
        assertThatThrownBy(() -> execAsOwner(w, insert, w.institution(), UUID.randomUUID(), "D4", "FIXED_AMOUNT", null, 0,
                "2027-01-01", "2027-12-31", by)).isInstanceOf(SQLException.class).hasMessageContaining("benefit_chk");
        assertThatThrownBy(() -> execAsOwner(w, insert, w.institution(), UUID.randomUUID(), "D5", "FIXED_AMOUNT", null, 10.005,
                "2027-01-01", "2027-12-31", by)).isInstanceOf(SQLException.class).hasMessageContaining("benefit_chk");
        assertThatThrownBy(() -> execAsOwner(w, insert, w.institution(), UUID.randomUUID(), "D6", "PERCENTAGE", 10, null,
                "2027-12-31", "2027-01-01", by)).isInstanceOf(SQLException.class).hasMessageContaining("dates_chk");
        assertThatThrownBy(() -> execAsOwner(w, insert, w.institution(), UUID.randomUUID(), "D7", "PERCENTAGE", 10, null,
                "1999-12-31", "2027-01-01", by)).isInstanceOf(SQLException.class).hasMessageContaining("window_chk");
    }

    @Test
    void aDiscountIsDeactivatedAndReactivatedAndNeverDeleted() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");

        DiscountView inactive = manageDiscounts().deactivate(w.administrator(), request(), created.id());
        DiscountView again = manageDiscounts().deactivate(w.administrator(), request(), created.id());
        DiscountView active = manageDiscounts().reactivate(w.administrator(), request(), created.id());

        assertThat(inactive.active()).isFalse();
        assertThat(inactive.version()).isEqualTo(1);
        assertThat(again.version()).isEqualTo(1);
        assertThat(active.active()).isTrue();
        assertThat(active.version()).isEqualTo(2);
        assertThat(auditEntries(w, "scholarships.discount.deactivated")).isEqualTo(1);
        assertThat(auditEntries(w, "scholarships.discount.reactivated")).isEqualTo(1);
    }

    @Test
    void theListIsFilteredSortedAndPagedByTheServer() throws SQLException {
        World w = world();
        campaign(w, "D-C", "10");
        DiscountView b = agreement(w, "D-A", "20");
        campaign(w, "D-B", "30");
        manageDiscounts().deactivate(w.administrator(), request(), b.id());

        Page<DiscountView> all = manageDiscounts().list(w.cashier(), request(), new DiscountFilter(null, null, null),
                PageRequest.of(0, 2, "code,asc", DiscountFilter.SORT_FIELDS, "code", true));
        Page<DiscountView> inactive = manageDiscounts().list(w.cashier(), request(), new DiscountFilter(false, null, null),
                sortedByCode());
        Page<DiscountView> campaigns = manageDiscounts().list(w.cashier(), request(), new DiscountFilter(null, "campaign", null),
                PageRequest.of(0, 50, "code,desc", DiscountFilter.SORT_FIELDS, "code", true));

        assertThat(all.total()).isEqualTo(3);
        assertThat(all.items()).extracting(DiscountView::code).containsExactly("D-A", "D-B");
        assertThat(inactive.items()).extracting(DiscountView::code).containsExactly("D-A");
        assertThat(campaigns.items()).extracting(DiscountView::code).containsExactly("D-C", "D-B");
        assertThatThrownBy(() -> manageDiscounts().list(w.cashier(), request(), new DiscountFilter(null, "nope", null),
                sortedByCode())).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> manageDiscounts().list(w.cashier(), request(), new DiscountFilter(null, null, "NOPE"),
                sortedByCode())).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> PageRequest.of(0, 201, null, DiscountFilter.SORT_FIELDS, "code", true))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> PageRequest.of(0, 10, "password", DiscountFilter.SORT_FIELDS, "code", true))
                .isInstanceOf(InvalidScholarshipsValueException.class);
    }

    // --- Requesting an assignment ---

    @Test
    void anAssignmentIsBornPendingAndAudited() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");

        AssignmentView assignment = requested(w, discount, 0, FEB, JUN);

        assertThat(assignment.status()).isEqualTo("pending");
        assertThat(assignment.discountCode()).isEqualTo("CONV-10");
        assertThat(assignment.studentId()).isEqualTo(w.student(0));
        assertThat(assignment.requestedBy()).isEqualTo(w.coordinator().accountId());
        assertThat(assignment.decision()).isNull();
        assertThat(auditEntries(w, "scholarships.discount_assignment.requested")).isEqualTo(1);
    }

    @Test
    void anAssignmentOfACampaignDiscountOfAnInactiveOneOrOutsideItsValidityIsRefused() throws SQLException {
        World w = world();
        DiscountView campaign = campaign(w, "CAMP-10", "10");
        DiscountView inactive = agreement(w, "CONV-OFF", "10");
        manageDiscounts().deactivate(w.administrator(), request(), inactive.id());
        DiscountView agreement = agreement(w, "CONV-10", "10");

        assertThatThrownBy(() -> requested(w, campaign, 0, FEB, JUN)).isInstanceOf(RejectedScholarshipFieldException.class)
                .extracting(e -> ((RejectedScholarshipFieldException) e).field()).isEqualTo("discountId");
        assertThatThrownBy(() -> requested(w, inactive, 0, FEB, JUN)).isInstanceOf(InactiveReferenceException.class);
        assertThatThrownBy(() -> requested(w, agreement, 0, FROM.minusDays(1), JUN))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThatThrownBy(() -> requested(w, agreement, 0, FEB, TO.plusDays(1)))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThatThrownBy(() -> requested(w, agreement, 0, JUN, FEB)).isInstanceOf(RejectedScholarshipFieldException.class);
        assertThat(requested(w, agreement, 0, FROM, TO).status()).isEqualTo("pending");
        assertThat(scalar(w.institution(), "select count(*) from scholarships_discount_assignment where institution_id = ?",
                w.institution())).isEqualTo(1);
    }

    @Test
    void anUnknownStudentOrDiscountIsRefusedAsNotFound() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");

        assertThatThrownBy(() -> requestAssignment().request(w.coordinator(), request(),
                new RequestDiscountAssignment.Command(discount.id(), UUID.randomUUID(), FEB, JUN)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> requestAssignment().request(w.coordinator(), request(),
                new RequestDiscountAssignment.Command(UUID.randomUUID(), w.student(0), FEB, JUN)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theDatabaseItselfRefusesTheAssignmentOfACampaignDiscountAndOneOutsideItsValidity() throws SQLException {
        World w = world();
        DiscountView campaign = campaign(w, "CAMP-10", "10");
        DiscountView agreement = agreement(w, "CONV-10", "10");
        String insert = "insert into scholarships_discount_assignment (institution_id, id, discount_id, student_id, valid_from, "
                + "valid_to, requested_by, discount_version, benefit_type, percent) values (?, ?, ?, ?, ?::date, ?::date, ?, 0, "
                + "'PERCENTAGE', 10)";
        UUID requester = UUID.randomUUID();

        assertThatThrownBy(() -> execAsActor(w, requester, insert, w.institution(), UUID.randomUUID(), campaign.id(), w.student(0),
                "2027-02-01", "2027-06-30", requester)).isInstanceOf(SQLException.class)
                .hasMessageContaining("only an agreement discount");
        assertThatThrownBy(() -> execAsActor(w, requester, insert, w.institution(), UUID.randomUUID(), agreement.id(), w.student(0),
                "2026-12-31", "2027-06-30", requester)).isInstanceOf(SQLException.class)
                .hasMessageContaining("inside the one of its discount");
        assertThatThrownBy(() -> execAsActor(w, requester, insert, w.institution(), UUID.randomUUID(), agreement.id(), w.student(0),
                "2027-02-01", "2028-01-01", requester)).isInstanceOf(SQLException.class)
                .hasMessageContaining("inside the one of its discount");
    }

    // --- Approving ---

    @Test
    void whoRequestedAnAssignmentCannotApproveNorRejectItAndNothingChanges() throws SQLException {
        World w = world();
        AssignmentView assignment = requested(w, agreement(w, "CONV-10", "10"), 0, FEB, JUN);

        assertThatThrownBy(() -> decideAssignment().approve(w.coordinator(), request(), "self-1", assignment.id()))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> decideAssignment().reject(w.coordinator(), request(), "self-2", assignment.id(), REASON))
                .isInstanceOf(SelfApprovalForbiddenException.class);

        assertThat(viewAssignments().get(w.administrator(), request(), assignment.id()).status()).isEqualTo("pending");
        assertThat(scalar(w.institution(), "select count(*) from scholarships_discount_assignment_decision where institution_id = ?",
                w.institution())).isZero();
    }

    @Test
    void theDatabaseItselfRefusesADecisionByTheRequesterWhateverTheCodeSays() throws SQLException {
        World w = world();
        AssignmentView assignment = requested(w, agreement(w, "CONV-10", "10"), 0, FEB, JUN);

        assertThatThrownBy(() -> transactionRunner().execute(w.coordinator().securityContext(request()), () -> {
            new com.confia.scholarships.infrastructure.JooqAssignmentDecisionRepository(dsl).insert(
                    new com.confia.kernel.InstitutionId(w.institution()),
                    new com.confia.scholarships.domain.AssignmentDecision(UUID.randomUUID(), assignment.id(),
                            com.confia.scholarships.domain.DecisionKind.APPROVED, null, w.coordinator().accountId(),
                            NOW.instant(), "raw-1", "a".repeat(64)));
            return null;
        })).isInstanceOf(SelfApprovalForbiddenException.class);
    }

    @Test
    void anotherPersonApprovesAndTheDecisionIsAuditedWithTheApprover() throws SQLException {
        World w = world();
        AssignmentView assignment = requested(w, agreement(w, "CONV-10", "10"), 0, FEB, JUN);

        AssignmentView approved = decideAssignment().approve(w.administrator(), request(), "approve-1", assignment.id());

        assertThat(approved.status()).isEqualTo("approved");
        assertThat(approved.decision().kind()).isEqualTo("approved");
        assertThat(approved.decision().decidedBy()).isEqualTo(w.administrator().accountId());
        assertThat(approved.decision().reason()).isNull();
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = "
                + "'scholarships.discount_assignment.approved' and before_value->>'requestedBy' = ? and approver_id = ?",
                w.institution(), w.coordinator().accountId().toString(), w.administrator().accountId())).isEqualTo(1);
    }

    @Test
    void aSecondApprovalOrARejectionOfAnApprovedAssignmentIsAStateError() throws SQLException {
        World w = world();
        AssignmentView assignment = approved(w, requested(w, agreement(w, "CONV-10", "10"), 0, FEB, JUN));

        assertThatThrownBy(() -> decideAssignment().approve(w.otherAdministrator(), request(), "again-1", assignment.id()))
                .isInstanceOf(InvalidAssignmentStateException.class);
        assertThatThrownBy(() -> decideAssignment().reject(w.otherAdministrator(), request(), "again-2", assignment.id(), REASON))
                .isInstanceOf(InvalidAssignmentStateException.class);
    }

    @Test
    void anAssignmentOfADiscountDeactivatedMeanwhileIsNotApproved() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        AssignmentView assignment = requested(w, discount, 0, FEB, JUN);
        manageDiscounts().deactivate(w.administrator(), request(), discount.id());

        assertThatThrownBy(() -> approved(w, assignment)).isInstanceOf(InactiveReferenceException.class);
        assertThat(viewAssignments().get(w.administrator(), request(), assignment.id()).status()).isEqualTo("pending");
    }

    // --- Overlap ---

    @Test
    void twoApprovedAssignmentsOfOneDiscountForOneStudentNeverOverlap() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        approved(w, requested(w, discount, 0, FEB, JUN));
        AssignmentView second = requested(w, discount, 0, JUN, LocalDate.parse("2027-09-30"));

        assertThatThrownBy(() -> approved(w, second)).isInstanceOf(DiscountAssignmentOverlapException.class);

        assertThat(viewAssignments().get(w.administrator(), request(), second.id()).status()).isEqualTo("pending");
        assertThat(scalar(w.institution(), "select count(*) from scholarships_discount_assignment_decision where institution_id = ?",
                w.institution())).isEqualTo(1);
        AssignmentView next = requested(w, discount, 0, LocalDate.parse("2027-07-01"), LocalDate.parse("2027-09-30"));
        assertThat(approved(w, next).status()).isEqualTo("approved");
    }

    @Test
    void anOverlapIsAllowedForAnotherStudentAnotherDiscountOrAfterARevocation() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        DiscountView other = agreement(w, "CONV-20", "20");
        AssignmentView first = approved(w, requested(w, discount, 0, FEB, JUN));

        assertThat(approved(w, requested(w, discount, 1, FEB, JUN)).status()).isEqualTo("approved");
        assertThat(approved(w, requested(w, other, 0, FEB, JUN)).status()).isEqualTo("approved");
        decideAssignment().revoke(w.administrator(), request(), "revoke-1", first.id(), REASON);
        assertThat(approved(w, requested(w, discount, 0, FEB, JUN)).status()).isEqualTo("approved");
    }

    @Test
    void aPendingOrRejectedAssignmentDoesNotBlockAnApprovedOne() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        AssignmentView rejected = requested(w, discount, 0, FEB, JUN);
        decideAssignment().reject(w.administrator(), request(), "reject-1", rejected.id(), REASON);
        requested(w, discount, 0, FEB, JUN);

        assertThat(approved(w, requested(w, discount, 0, FEB, JUN)).status()).isEqualTo("approved");
    }

    // --- Rejecting and revoking ---

    @Test
    void aRejectionKeepsItsReasonOnlyInTheDecisionAndTheAuditHoldsItsHashAndLength() throws SQLException {
        World w = world();
        AssignmentView assignment = requested(w, agreement(w, "CONV-10", "10"), 0, FEB, JUN);

        AssignmentView rejected = decideAssignment().reject(w.administrator(), request(), "reject-1", assignment.id(), REASON);

        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(rejected.decision().reason()).isEqualTo(REASON);
        assertThat(auditReason(w, "scholarships.discount_assignment.rejected")).isEqualTo(sha256(REASON));
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = "
                + "'scholarships.discount_assignment.rejected' and after_value::text like ? and after_value::text not like ?",
                w.institution(), "%\"reasonLength\"%", "%familia%")).isEqualTo(1);
    }

    @Test
    void anApprovedAssignmentIsRevokedWithAReasonAndAPendingOneIsNot() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        AssignmentView pending = requested(w, discount, 0, FEB, JUN);
        AssignmentView approved = approved(w, requested(w, discount, 1, FEB, JUN));

        assertThatThrownBy(() -> decideAssignment().revoke(w.administrator(), request(), "revoke-0", pending.id(), REASON))
                .isInstanceOf(InvalidAssignmentStateException.class);
        AssignmentView revoked = decideAssignment().revoke(w.coordinator(), request(), "revoke-1", approved.id(), REASON);

        assertThat(revoked.status()).isEqualTo("revoked");
        assertThat(revoked.revocation().kind()).isEqualTo("revoked");
        assertThat(revoked.decision().kind()).isEqualTo("approved");
        assertThat(auditReason(w, "scholarships.discount_assignment.revoked")).isEqualTo(sha256(REASON));
        assertThatThrownBy(() -> decideAssignment().revoke(w.administrator(), request(), "revoke-2", approved.id(), REASON))
                .isInstanceOf(InvalidAssignmentStateException.class);
    }

    @Test
    void aReasonOutOfItsBoundsIsRefusedBeforeAnythingIsWritten() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        AssignmentView pending = requested(w, discount, 0, FEB, JUN);
        AssignmentView approved = approved(w, requested(w, discount, 1, FEB, JUN));

        assertThatThrownBy(() -> decideAssignment().reject(w.administrator(), request(), "r-1", pending.id(), "corto"))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThatThrownBy(() -> decideAssignment().reject(w.administrator(), request(), "r-2", pending.id(), "x".repeat(501)))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThatThrownBy(() -> decideAssignment().revoke(w.administrator(), request(), "r-3", approved.id(), null))
                .isInstanceOf(RejectedScholarshipFieldException.class);
        assertThat(scalar(w.institution(), "select count(*) from scholarships_discount_assignment_decision where institution_id = ?",
                w.institution())).isEqualTo(1);
    }

    private String auditReason(World w, String action) throws SQLException {
        try (Connection c = com.confia.support.StaffRoleFixtures.ownerTransaction(w.institution());
                PreparedStatement s = c.prepareStatement(
                        "select reason from shared_audit_log where institution_id = ? and action = ?")) {
            s.setObject(1, w.institution());
            s.setString(2, action);
            try (var rs = s.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- The keys ---

    @Test
    void theSameKeyAndTheSameRequestAnswerTheSameDecisionOnce() throws SQLException {
        World w = world();
        AssignmentView assignment = requested(w, agreement(w, "CONV-10", "10"), 0, FEB, JUN);

        AssignmentView first = decideAssignment().approve(w.administrator(), request(), "approve-1", assignment.id());
        AssignmentView replay = decideAssignment().approve(w.administrator(), request(), "approve-1", assignment.id());

        assertThat(replay.decision().id()).isEqualTo(first.decision().id());
        assertThat(replay.status()).isEqualTo("approved");
        assertThat(scalar(w.institution(), "select count(*) from scholarships_discount_assignment_decision where institution_id = ?",
                w.institution())).isEqualTo(1);
        assertThat(auditEntries(w, "scholarships.discount_assignment.approved")).isEqualTo(1);
    }

    @Test
    void theSameKeyForAnotherRequestOrByAnotherPersonIsAMismatchAndAMissingKeyIsRefused() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        AssignmentView assignment = requested(w, discount, 0, FEB, JUN);
        AssignmentView other = requested(w, discount, 1, FEB, JUN);
        decideAssignment().approve(w.administrator(), request(), "approve-1", assignment.id());

        assertThatThrownBy(() -> decideAssignment().approve(w.administrator(), request(), "approve-1", other.id()))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> decideAssignment().approve(w.otherAdministrator(), request(), "approve-1", assignment.id()))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> decideAssignment().reject(w.administrator(), request(), "approve-1", other.id(), REASON))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> decideAssignment().approve(w.administrator(), request(), null, other.id()))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> decideAssignment().approve(w.administrator(), request(), "  ", other.id()))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> decideAssignment().approve(w.administrator(), request(), "has space", other.id()))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThat(viewAssignments().get(w.administrator(), request(), other.id()).status()).isEqualTo("pending");
    }

    @Test
    void twoDecisionsOnTheSameAssignmentAtTheSameTimeLeaveExactlyOneWinner() throws Exception {
        World w = world();
        AssignmentView assignment = requested(w, agreement(w, "CONV-10", "10"), 0, FEB, JUN);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(() -> {
            go.await();
            try {
                decideAssignment().approve(w.administrator(), request(), "race-approve", assignment.id());
                return "approved";
            } catch (InvalidAssignmentStateException e) {
                return "lost";
            }
        });
        tasks.add(() -> {
            go.await();
            try {
                decideAssignment().reject(w.otherAdministrator(), request(), "race-reject", assignment.id(), REASON);
                return "rejected";
            } catch (InvalidAssignmentStateException e) {
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
            assertThat(scalar(w.institution(), "select count(*) from scholarships_discount_assignment_decision "
                    + "where institution_id = ?", w.institution())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Reading and isolation ---

    @Test
    void theListOfAssignmentsIsFilteredByStudentStatusAndDiscount() throws SQLException {
        World w = world();
        DiscountView one = agreement(w, "CONV-10", "10");
        DiscountView two = agreement(w, "CONV-20", "20");
        requested(w, one, 0, FEB, JUN);
        approved(w, requested(w, one, 1, FEB, JUN));
        requested(w, two, 0, FEB, JUN);

        PageRequest page = PageRequest.of(0, 50, null, AssignmentFilter.SORT_FIELDS, "requestedAt", false);
        assertThat(viewAssignments().list(w.cashier(), request(), new AssignmentFilter(null, null, null), page).total())
                .isEqualTo(3);
        assertThat(viewAssignments().list(w.cashier(), request(), new AssignmentFilter(w.student(0), null, null), page).total())
                .isEqualTo(2);
        assertThat(viewAssignments().list(w.cashier(), request(), new AssignmentFilter(null, "approved", null), page).total())
                .isEqualTo(1);
        assertThat(viewAssignments().list(w.cashier(), request(), new AssignmentFilter(null, null, two.id()), page).items())
                .extracting(AssignmentView::discountCode).containsExactly("CONV-20");
        assertThatThrownBy(() -> viewAssignments().list(w.cashier(), request(), new AssignmentFilter(null, "nope", null), page))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThat(viewAssignments().list(w.cashier(), request(), new AssignmentFilter(null, null, null), page).items())
                .allSatisfy(view -> assertThat(view.decision()).isNull());
    }

    @Test
    void anotherInstitutionSeesNothingAndItsIdentifiersLookLikeThoseThatDoNotExist() throws SQLException {
        World mine = world();
        World theirs = world();
        DiscountView created = agreement(mine, "CONV-10", "10");
        AssignmentView assignment = requested(mine, created, 0, FEB, JUN);

        assertThatThrownBy(() -> manageDiscounts().get(theirs.administrator(), request(), created.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> viewAssignments().get(theirs.administrator(), request(), assignment.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> decideAssignment().approve(theirs.administrator(), request(), "x-1", assignment.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> decideAssignment().reject(theirs.administrator(), request(), "x-2", assignment.id(), REASON))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> decideAssignment().revoke(theirs.administrator(), request(), "x-3", assignment.id(), REASON))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> manageDiscounts().deactivate(theirs.administrator(), request(), created.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> manageDiscounts().reactivate(theirs.administrator(), request(), created.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> requestAssignment().request(theirs.coordinator(), request(),
                new RequestDiscountAssignment.Command(created.id(), theirs.student(0), FEB, JUN)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> manageDiscounts().update(theirs.administrator(), request(), created.id(), 0,
                draft(theirs, "30", true))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(manageDiscounts().list(theirs.administrator(), request(), new DiscountFilter(null, null, null),
                sortedByCode()).total()).isZero();
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_discount")).isZero();
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_discount_concept")).isZero();
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_discount_assignment")).isZero();
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_discount_assignment_decision")).isZero();
        assertThat(countVisibleAsApplication(mine.institution(), "scholarships_discount")).isEqualTo(1);
        assertThat(countVisibleAsApplication(mine.institution(), "scholarships_discount_assignment")).isEqualTo(1);
    }

    @Test
    void theDecisionsOfAnInstitutionAreInvisibleToTheOtherOneOnceTheyExist() throws SQLException {
        World mine = world();
        World theirs = world();
        approved(mine, requested(mine, agreement(mine, "CONV-10", "10"), 0, FEB, JUN));

        // There is one decision, so a count of zero is the work of the row-level security and not an empty table.
        assertThat(countVisibleAsApplication(mine.institution(), "scholarships_discount_assignment_decision")).isEqualTo(1);
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_discount_assignment_decision")).isZero();
    }

    @Test
    void theApplicationRoleCannotWriteRowsOfAnotherInstitutionNorChangeThem() throws SQLException {
        World mine = world();
        World theirs = world();
        DiscountView created = agreement(mine, "CONV-10", "10");
        AssignmentView assignment = approved(mine, requested(mine, created, 0, FEB, JUN));

        // WITH CHECK: with the context of the other institution a row of this one cannot be written.
        assertThatThrownBy(() -> asApplication(theirs.institution(), "insert into scholarships_discount (institution_id, id, "
                + "code, name, kind, benefit_type, percent, valid_from, valid_to, is_stackable, created_by) values (?, ?, "
                + "'ROBADO', 'D', 'campaign', 'PERCENTAGE', 10, date '2027-01-01', date '2027-12-31', true, ?)",
                mine.institution(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
        // USING: the rows of this institution are not even found by an update with the context of the other one.
        assertThat(asApplication(theirs.institution(), "update scholarships_discount_assignment set status = 'revoked' "
                + "where institution_id = ? and id = ?", mine.institution(), assignment.id())).isZero();
        assertThat(asApplication(theirs.institution(), "update scholarships_discount set is_active = false "
                + "where institution_id = ? and id = ?", mine.institution(), created.id())).isZero();
        assertThat(viewAssignments().get(mine.administrator(), request(), assignment.id()).status()).isEqualTo("approved");
        assertThat(manageDiscounts().get(mine.administrator(), request(), created.id()).active()).isTrue();
    }

    /** One statement as the least-privilege role of the application with the context of an institution; answers the rows changed. */
    private static int asApplication(UUID institutionContext, String sql, Object... parameters) throws SQLException {
        try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            try (PreparedStatement context = c.prepareStatement("select set_config('app.institution_id', ?, true)")) {
                context.setString(1, institutionContext.toString());
                context.execute();
            }
            try (PreparedStatement s = c.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    s.setObject(i + 1, parameters[i]);
                }
                int changed = s.executeUpdate();
                c.rollback();
                return changed;
            }
        }
    }

    @Test
    void aDiscountOfAnotherInstitutionCannotBeAssignedToAStudentOfThisOne() throws SQLException {
        World mine = world();
        World theirs = world();
        DiscountView foreign = agreement(theirs, "CONV-10", "10");

        assertThatThrownBy(() -> requestAssignment().request(mine.coordinator(), request(),
                new RequestDiscountAssignment.Command(foreign.id(), mine.student(0), FEB, JUN)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theApplicationRoleCannotUpdateTheColumnsThatNeverChangeNorDeleteNorChangeADecision() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");
        AssignmentView assignment = approved(w, requested(w, created, 0, FEB, JUN));

        assertThatThrownBy(() -> asApplication(w.institution(), "update scholarships_discount set code = 'OTRO' "
                + "where institution_id = ? and id = ?", w.institution(), created.id()))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApplication(w.institution(), "delete from scholarships_discount_assignment "
                + "where institution_id = ? and id = ?", w.institution(), assignment.id()))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApplication(w.institution(), "update scholarships_discount_assignment_decision "
                + "set reason = 'x' where institution_id = ?", w.institution()))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> execAsOwner(w, "update scholarships_discount_assignment_decision set reason = 'x' "
                + "where institution_id = ?", w.institution())).isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
    }

    // --- H2: the approval is bound to the terms the assignment was requested for ---

    @Test
    void theAssignmentKeepsTheVersionAndTheBenefitOfTheDiscountItWasRequestedForAndTheAuditCarriesThem() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");

        AssignmentView requested = requested(w, discount, 0, FEB, JUN);
        AssignmentView approved = approved(w, requested);

        assertThat(requested.discountVersion()).isEqualTo(discount.version());
        assertThat(requested.benefitType()).isEqualTo("PERCENTAGE");
        assertThat(requested.percent()).isEqualTo("10.00");
        assertThat(requested.fixedAmount()).isNull();
        assertThat(approved.discountVersion()).isEqualTo(discount.version());
        assertThat(viewAssignments().get(w.cashier(), request(), requested.id()).percent()).isEqualTo("10.00");
        assertThat(scalar(w.institution(), "select count(*) from shared_audit_log where institution_id = ? and action in "
                + "('scholarships.discount_assignment.requested', 'scholarships.discount_assignment.approved') "
                + "and after_value->>'discountVersion' = '0' and after_value->>'benefitType' = 'PERCENTAGE' "
                + "and (after_value->>'percent')::numeric = 10", w.institution())).isEqualTo(2);
    }

    @Test
    void aFixedAmountAssignmentKeepsItsAmountToo() throws SQLException {
        World w = world();
        DiscountView discount = manageDiscounts().create(w.administrator(), request(), "FIJO-100", "agreement",
                new ManageDiscounts.Draft("Fijo", "FIXED_AMOUNT", null, "100.00", FROM, TO, true, Set.of(w.env().concept())));

        AssignmentView requested = requested(w, discount, 0, FEB, JUN);

        assertThat(requested.benefitType()).isEqualTo("FIXED_AMOUNT");
        assertThat(requested.percent()).isNull();
        assertThat(requested.fixedAmount().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void anApprovalIsRefusedWhenTheDiscountChangedAfterTheRequestWhateverWasChangedAndNothingIsWritten() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        AssignmentView raised = requested(w, discount, 0, FEB, JUN);
        // The one who requested it raises the benefit before anybody approves (an agreement discount is edited at once).
        DiscountView edited = manageDiscounts().update(w.coordinator(), request(), discount.id(), 0, draft(w, "90", true));

        assertThatThrownBy(() -> decideAssignment().approve(w.administrator(), request(), "approve-raised", raised.id()))
                .isInstanceOf(DiscountTermsChangedException.class);
        // Even an edit that changes no term (the name) moves the version: the approver approves what was shown or nothing.
        AssignmentView renamed = requested(w, edited, 1, FEB, JUN);
        manageDiscounts().update(w.coordinator(), request(), edited.id(), edited.version(), new ManageDiscounts.Draft("Otro nombre",
                "PERCENTAGE", "90", null, FROM, TO, true, Set.of(w.env().concept())));
        assertThatThrownBy(() -> decideAssignment().approve(w.administrator(), request(), "approve-renamed", renamed.id()))
                .isInstanceOf(DiscountTermsChangedException.class);

        assertThat(viewAssignments().get(w.administrator(), request(), raised.id()).status()).isEqualTo("pending");
        assertThat(scalar(w.institution(), "select count(*) from scholarships_discount_assignment_decision where institution_id = ?",
                w.institution())).isZero();
        assertThat(auditEntries(w, "scholarships.discount_assignment.approved")).isZero();
        // Rejecting needs no terms: whoever decides may still refuse it, and a new request for the terms of today is approved.
        assertThat(decideAssignment().reject(w.administrator(), request(), "reject-raised", raised.id(), REASON).status())
                .isEqualTo("rejected");
        DiscountView current = manageDiscounts().get(w.administrator(), request(), discount.id());
        assertThat(approved(w, requested(w, current, 0, FEB, JUN)).status()).isEqualTo("approved");
    }

    @Test
    void theDatabaseItselfRefusesToApproveAnAssignmentForOtherTermsThanTheOnesItWasRequestedFor() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        AssignmentView requested = requested(w, discount, 0, FEB, JUN);
        manageDiscounts().update(w.coordinator(), request(), discount.id(), 0, draft(w, "90", true));

        assertThatThrownBy(() -> execAsActor(w, w.administrator().accountId(),
                "update scholarships_discount_assignment set status = 'approved' where institution_id = ? and id = ?",
                w.institution(), requested.id())).isInstanceOf(SQLException.class)
                .hasMessageContaining("terms it was requested for");
        assertThat(viewAssignments().get(w.administrator(), request(), requested.id()).status()).isEqualTo("pending");
    }

    @Test
    void anAssignmentIsBornOnlyWithTheTermsTheDiscountHasNowAndTheyNeverChange() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        AssignmentView requested = requested(w, discount, 0, FEB, JUN);
        UUID requester = w.coordinator().accountId();
        String insert = "insert into scholarships_discount_assignment (institution_id, id, discount_id, student_id, valid_from, "
                + "valid_to, requested_by, discount_version, benefit_type, percent) values (?, ?, ?, ?, ?::date, ?::date, ?, ?, ?, ?)";

        assertThatThrownBy(() -> execAsActor(w, requester, insert, w.institution(), UUID.randomUUID(), discount.id(), w.student(1),
                "2027-02-01", "2027-06-30", requester, 5, "PERCENTAGE", 10)).isInstanceOf(SQLException.class)
                .hasMessageContaining("terms the discount has now");
        assertThatThrownBy(() -> execAsActor(w, requester, insert, w.institution(), UUID.randomUUID(), discount.id(), w.student(1),
                "2027-02-01", "2027-06-30", requester, 0, "PERCENTAGE", 50)).isInstanceOf(SQLException.class)
                .hasMessageContaining("terms the discount has now");
        assertThatThrownBy(() -> execAsActor(w, requester, "update scholarships_discount_assignment set percent = 99 "
                + "where institution_id = ? and id = ?", w.institution(), requested.id())).isInstanceOf(SQLException.class)
                .hasMessageContaining("changes only its status");
    }

    @Test
    void anAssignmentFromBeforeTheTermsWereKeptIsNeverApprovedByTheApplicationNorTheDatabase() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        UUID legacy = UUID.randomUUID();
        // The shape V39 gave it: no snapshot. Only the superuser with the triggers off can write one now (as the migrations of that time did).
        try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs("postgres");
                java.sql.Statement replica = c.createStatement()) {
            replica.execute("set session_replication_role = replica");
            try (PreparedStatement insert = c.prepareStatement("insert into scholarships_discount_assignment (institution_id, id, "
                    + "discount_id, student_id, valid_from, valid_to, requested_by) values (?, ?, ?, ?, date '2027-02-01', "
                    + "date '2027-06-30', ?)")) {
                insert.setObject(1, w.institution());
                insert.setObject(2, legacy);
                insert.setObject(3, discount.id());
                insert.setObject(4, w.student(0));
                insert.setObject(5, w.coordinator().accountId());
                insert.executeUpdate();
            }
        }

        assertThatThrownBy(() -> decideAssignment().approve(w.administrator(), request(), "approve-legacy", legacy))
                .isInstanceOf(DiscountTermsChangedException.class);
        assertThatThrownBy(() -> execAsActor(w, w.administrator().accountId(),
                "update scholarships_discount_assignment set status = 'approved' where institution_id = ? and id = ?",
                w.institution(), legacy)).isInstanceOf(SQLException.class).hasMessageContaining("terms it was requested for");
        assertThat(viewAssignments().get(w.administrator(), request(), legacy).discountVersion()).isNull();
    }

    // --- L1: the requester is the account of the session ---

    @Test
    void theDatabaseRefusesAnAssignmentRequestedInTheNameOfAnotherAccountOrOfNobody() throws SQLException {
        World w = world();
        DiscountView discount = agreement(w, "CONV-10", "10");
        String insert = "insert into scholarships_discount_assignment (institution_id, id, discount_id, student_id, valid_from, "
                + "valid_to, requested_by, discount_version, benefit_type, percent) values (?, ?, ?, ?, ?::date, ?::date, ?, 0, "
                + "'PERCENTAGE', 10)";

        // The session is the coordinator's and the row names the administrator: nobody requests in the name of another.
        assertThatThrownBy(() -> execAsActor(w, w.coordinator().accountId(), insert, w.institution(), UUID.randomUUID(),
                discount.id(), w.student(0), "2027-02-01", "2027-06-30", w.administrator().accountId()))
                .isInstanceOf(SQLException.class).hasMessageContaining("requested by the person of the session");
        // No session account at all.
        assertThatThrownBy(() -> execAsOwner(w, insert, w.institution(), UUID.randomUUID(), discount.id(), w.student(0),
                "2027-02-01", "2027-06-30", w.administrator().accountId())).isInstanceOf(SQLException.class)
                .hasMessageContaining("requested by the person of the session");
        execAsActor(w, w.coordinator().accountId(), insert, w.institution(), UUID.randomUUID(), discount.id(), w.student(0),
                "2027-02-01", "2027-06-30", w.coordinator().accountId());
    }

    // --- S8 in the database: the stackable flag of a discount in use is locked ---

    @Test
    void theDatabaseItselfRefusesToChangeTheStackableFlagOfADiscountInUse() throws SQLException {
        World w = world();
        DiscountView created = agreement(w, "CONV-10", "10");
        assertThat(created.stackable()).isTrue();
        // Not in use yet: it moves.
        execAsOwner(w, "update scholarships_discount set is_stackable = false, version = version + 1 where institution_id = ? "
                + "and id = ?", w.institution(), created.id());
        execAsOwner(w, "update scholarships_discount set is_stackable = true, version = version + 1 where institution_id = ? "
                + "and id = ?", w.institution(), created.id());
        approved(w, requested(w, manageDiscounts().get(w.administrator(), request(), created.id()), 0, FEB, JUN));

        assertThatThrownBy(() -> execAsOwner(w, "update scholarships_discount set is_stackable = false "
                + "where institution_id = ? and id = ?", w.institution(), created.id())).isInstanceOf(SQLException.class)
                .hasMessageContaining("stackable flag of a discount in use never changes");
    }

    // --- L3: isolation of the view of the discounts in use and of a session without an institution ---

    @Test
    void theViewOfTheDiscountsInUseShowsOneInstitutionOnlyItsOwnAndNothingWithoutAnInstitution() throws SQLException {
        World mine = world();
        World theirs = world();
        approved(mine, requested(mine, agreement(mine, "CONV-10", "10"), 0, FEB, JUN));

        // There is one discount in use, so a count of zero is the work of the row-level security under the view and not an empty one.
        assertThat(countVisibleAsApplication(mine.institution(), "scholarships_discount_in_use")).isEqualTo(1);
        assertThat(countVisibleAsApplication(theirs.institution(), "scholarships_discount_in_use")).isZero();
        assertThat(countWithSetting(null, "scholarships_discount_in_use")).isZero();
        assertThat(countWithSetting("", "scholarships_discount_in_use")).isZero();
    }

    @Test
    void aSessionWithoutAnInstitutionSeesNothingOfTheDiscountTablesWhetherTheSettingIsMissingOrEmpty() throws SQLException {
        World w = world();
        approved(w, requested(w, agreement(w, "CONV-10", "10"), 0, FEB, JUN));

        for (String table : List.of("scholarships_discount", "scholarships_discount_concept", "scholarships_discount_assignment",
                "scholarships_discount_assignment_decision", "scholarships_discount_change_decision")) {
            assertThat(countWithSetting(null, table)).as(table + " with the setting never made").isZero();
            assertThat(countWithSetting("", table)).as(table + " with an empty setting").isZero();
        }
        assertThat(countVisibleAsApplication(w.institution(), "scholarships_discount")).as("the rows exist").isEqualTo(1);
        // Nor can such a session write.
        assertThatThrownBy(() -> asApplication("", "insert into scholarships_discount_change_decision (institution_id, id, "
                + "discount_id, discount_version, decision, decided_by, request_key, request_hash) values (?, ?, ?, 0, 'approved', "
                + "?, 'k', ?)", w.institution(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64)))
                .isInstanceOf(SQLException.class);
    }

    /** One statement as the application role with the setting of the institution as given (never made when {@code null}). */
    private static int asApplication(String setting, String sql, Object... parameters) throws SQLException {
        try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            if (setting != null) {
                try (PreparedStatement context = c.prepareStatement("select set_config('app.institution_id', ?, true)")) {
                    context.setString(1, setting);
                    context.execute();
                }
            }
            try (PreparedStatement s = c.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    s.setObject(i + 1, parameters[i]);
                }
                int changed = s.executeUpdate();
                c.rollback();
                return changed;
            }
        }
    }
}
