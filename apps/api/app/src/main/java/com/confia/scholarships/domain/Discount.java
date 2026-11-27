package com.confia.scholarships.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A discount of the institution (docs/02 section 3.3, docs/cortes/scholarships-discounts.md): a unique code, a name, a kind
 * (campaign or agreement), a benefit (a percentage or a fixed amount, the same {@link Benefit} of a scholarship), the payment
 * concepts it covers (one or more), a validity (both ends included) and whether it is stackable. It is deactivated and reactivated,
 * never deleted. An inactive discount takes no new assignment and stops applying to the charges that are issued from then on. Once it
 * is in use (an assignment of it was approved, or a charge was issued with it) the type and the value of its benefit and its stackable
 * flag never change, its validity only shortens and its covered concepts are only removed: widening a discount is a new discount, so
 * a charge issued with it is always explained with what it was. Editing asks for the version the editor saw.
 *
 * <p><b>Approval of a campaign discount (S7).</b> A campaign discount applies by itself to every charge, so it is born {@code pending}
 * and applies only once a person other than the one who created it approves it. Widening it (another type of benefit or a greater
 * value, a validity that starts earlier or ends later, a concept more, the stackable flag turned on) and reactivating it do not change
 * the terms in force: they leave a {@link DiscountProposal} that a different person approves (it becomes the terms) or rejects (it is
 * dropped). Narrowing and deactivating are immediate. One proposal waits at a time, and a decision names the version it saw. An
 * agreement discount needs none of this: it applies only through an approved assignment, which has its own second person.
 *
 * @param approval whether the terms in force were approved; always {@code APPROVED} for an agreement discount
 * @param proposal the terms that wait for approval, or {@code null}
 */
public record Discount(UUID id, String code, String name, DiscountKind kind, Benefit benefit, LocalDate validFrom,
        LocalDate validTo, Set<UUID> concepts, boolean stackable, boolean active, long version, UUID createdBy,
        Instant createdAt, Instant updatedAt, ApprovalStatus approval, DiscountProposal proposal) {

    private static final LocalDate WINDOW_START = LocalDate.of(2000, 1, 1);
    private static final LocalDate WINDOW_END = LocalDate.of(2100, 12, 31);

    public Discount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(benefit, "benefit");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(approval, "approval");
        if (version < 0 || (kind == DiscountKind.AGREEMENT && (approval != ApprovalStatus.APPROVED || proposal != null))
                || (proposal != null && approval != ApprovalStatus.APPROVED)) {
            throw new InvalidScholarshipsValueException();
        }
        code = Texts.code(code);
        name = Texts.name(name);
        concepts = requireConcepts(concepts);
        if (validFrom == null || validFrom.isBefore(WINDOW_START) || validFrom.isAfter(WINDOW_END)) {
            throw new InvalidScholarshipsValueException("validFrom", "invalid");
        }
        if (validTo == null || validTo.isBefore(validFrom) || validTo.isAfter(WINDOW_END)) {
            throw new InvalidScholarshipsValueException("validTo", "invalid");
        }
    }

    /** A new discount, active, at version 0: an agreement one is approved, a campaign one waits for another person. */
    public static Discount create(UUID id, String code, String name, DiscountKind kind, Benefit benefit, LocalDate validFrom,
            LocalDate validTo, Set<UUID> concepts, boolean stackable, UUID createdBy, Instant now) {
        Objects.requireNonNull(kind, "kind");
        ApprovalStatus approval = kind == DiscountKind.CAMPAIGN ? ApprovalStatus.PENDING : ApprovalStatus.APPROVED;
        return new Discount(id, code, name, kind, benefit, validFrom, validTo, concepts, stackable, true, 0, createdBy, now, now,
                approval, null);
    }

    /**
     * The same discount with a new name, benefit, validity, stackable flag and set of covered concepts and the version advanced; the
     * code, the kind and the active flag stay. A campaign discount that this edit would widen is not changed: the result carries the
     * edit as a {@link #proposal() proposal} for another person to approve.
     *
     * @param expectedVersion the version the editor saw
     * @param editor who edits; the one who proposes a widening, who never approves it
     * @param inUse whether an assignment of this discount was ever approved or a charge was issued with it
     * @throws StaleVersionException when the version is not the current one
     * @throws DiscountPendingApprovalException when the discount waits for an approval, was rejected or has a proposal waiting
     * @throws DiscountBenefitLockedException when the benefit or the stackable flag changes and the discount is in use
     * @throws DiscountScopeLockedException when it is in use and the validity widens or a concept is added
     */
    public Discount edit(long expectedVersion, UUID editor, String newName, Benefit newBenefit, LocalDate newFrom, LocalDate newTo,
            Set<UUID> newConcepts, boolean newStackable, boolean inUse, Instant now) {
        Objects.requireNonNull(editor, "editor");
        if (expectedVersion != version) {
            throw new StaleVersionException();
        }
        requireSettled();
        if (inUse && (!benefit.sameAs(Objects.requireNonNull(newBenefit, "newBenefit")) || newStackable != stackable)) {
            throw new DiscountBenefitLockedException();
        }
        Discount edited = new Discount(id, code, newName, kind, newBenefit, newFrom, newTo, newConcepts, newStackable, active,
                version + 1, createdBy, createdAt, now, approval, null);
        if (inUse && widensScope(edited)) {
            throw new DiscountScopeLockedException();
        }
        if (kind == DiscountKind.CAMPAIGN && widens(edited)) {
            return proposing(new DiscountProposal(edited.name, edited.benefit, edited.validFrom, edited.validTo, edited.stackable,
                    false, edited.concepts, editor, now), now);
        }
        return edited;
    }

    /** Whether {@code edited} starts earlier, ends later, or covers a concept this discount did not. */
    private boolean widensScope(Discount edited) {
        return edited.validFrom.isBefore(validFrom) || edited.validTo.isAfter(validTo) || !concepts.containsAll(edited.concepts);
    }

    /** Whether {@code edited} could give a benefit this discount does not: any widening of the scope, a greater benefit, or stackable on. */
    private boolean widens(Discount edited) {
        return widensScope(edited) || edited.benefit.widens(benefit) || (edited.stackable && !stackable);
    }

    /** This discount with the proposal waiting and the version advanced; the terms in force stay. */
    private Discount proposing(DiscountProposal proposed, Instant now) {
        return new Discount(id, code, name, kind, benefit, validFrom, validTo, concepts, stackable, active, version + 1, createdBy,
                createdAt, now, approval, proposed);
    }

    /**
     * The discount active or inactive; the same one when it already is, so the version only moves with a real change. Deactivating
     * is immediate. Reactivating an agreement discount is immediate; reactivating a campaign discount is a proposal that another
     * person approves.
     *
     * @param by who asks; the one who proposes a reactivation
     * @throws DiscountPendingApprovalException when the discount waits for an approval or was rejected, or when it is to be
     *         reactivated and a proposal is already waiting
     */
    public Discount withActive(boolean newActive, UUID by, Instant now) {
        Objects.requireNonNull(by, "by");
        if (newActive == active) {
            return this;
        }
        if (approval != ApprovalStatus.APPROVED) {
            throw new DiscountPendingApprovalException();
        }
        if (newActive && kind == DiscountKind.CAMPAIGN) {
            requireSettled();
            return proposing(new DiscountProposal(name, benefit, validFrom, validTo, stackable, true, concepts, by, now), now);
        }
        return new Discount(id, code, name, kind, benefit, validFrom, validTo, concepts, stackable, newActive, version + 1,
                createdBy, createdAt, now, approval, proposal);
    }

    /** Whether a person other than {@link #requestedBy()} must decide before the terms in force are the ones asked for. */
    public boolean awaitingApproval() {
        return approval == ApprovalStatus.PENDING || proposal != null;
    }

    /** Who asked for what waits: the creator of a discount waiting for approval, or the author of the proposal; the creator otherwise. */
    public UUID requestedBy() {
        return proposal != null ? proposal.requestedBy() : createdBy;
    }

    /**
     * The approval by {@code by} of the discount at the version {@code expectedVersion}, which the caller applies with {@link
     * #decided}.
     *
     * @throws InvalidDiscountChangeStateException when nothing waits for a decision
     * @throws SelfApprovalForbiddenException when {@code by} asked for what waits
     * @throws StaleVersionException when the version is not the one the decider saw
     */
    public DiscountDecision approveChange(UUID decisionId, UUID by, long expectedVersion, Instant now, String requestKey,
            String requestHash) {
        requireDecidableBy(by, expectedVersion);
        return new DiscountDecision(decisionId, id, version, DecisionKind.APPROVED, null, by, now, requestKey, requestHash);
    }

    /** The rejection by {@code by}, with the reason; same conditions as {@link #approveChange}. */
    public DiscountDecision rejectChange(UUID decisionId, UUID by, long expectedVersion, DecisionReason reason, Instant now,
            String requestKey, String requestHash) {
        requireDecidableBy(by, expectedVersion);
        return new DiscountDecision(decisionId, id, version, DecisionKind.REJECTED, reason.text(), by, now, requestKey, requestHash);
    }

    private void requireDecidableBy(UUID by, long expectedVersion) {
        Objects.requireNonNull(by, "by");
        if (!awaitingApproval()) {
            throw new InvalidDiscountChangeStateException();
        }
        if (requestedBy().equals(by)) {
            throw new SelfApprovalForbiddenException();
        }
        if (expectedVersion != version) {
            throw new StaleVersionException();
        }
    }

    /**
     * This discount once the decision is made, with the version advanced. An approval makes the proposal the terms in force (and
     * turns the discount on when it proposed the reactivation) or approves a creation; a rejection drops the proposal, or rejects a
     * creation for good.
     */
    public Discount decided(DiscountDecision decision, Instant now) {
        Objects.requireNonNull(decision, "decision");
        boolean approved = decision.kind() == DecisionKind.APPROVED;
        if (proposal != null) {
            if (!approved) {
                return new Discount(id, code, name, kind, benefit, validFrom, validTo, concepts, stackable, active, version + 1,
                        createdBy, createdAt, now, approval, null);
            }
            return new Discount(id, code, proposal.name(), kind, proposal.benefit(), proposal.validFrom(), proposal.validTo(),
                    proposal.concepts(), proposal.stackable(), active || proposal.reactivate(), version + 1, createdBy, createdAt,
                    now, approval, null);
        }
        return new Discount(id, code, name, kind, benefit, validFrom, validTo, concepts, stackable, active, version + 1, createdBy,
                createdAt, now, approved ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED, null);
    }

    private void requireSettled() {
        if (awaitingApproval() || approval != ApprovalStatus.APPROVED) {
            throw new DiscountPendingApprovalException();
        }
    }

    /** @throws InactiveReferenceException when the discount is inactive: it takes no new assignment */
    public void requireActive() {
        if (!active) {
            throw new InactiveReferenceException();
        }
    }

    /**
     * Checks that the discount can be assigned to a student for the days given: it is of agreement kind, it is active and the days
     * are inside its validity, both ends included.
     *
     * @throws InvalidScholarshipsValueException when it is not an agreement discount, or the days are outside its validity
     * @throws InactiveReferenceException when it is inactive
     */
    public void requireAssignable(LocalDate from, LocalDate to) {
        if (kind != DiscountKind.AGREEMENT) {
            throw new InvalidScholarshipsValueException("discountId", "not-agreement");
        }
        requireActive();
        if (from == null || from.isBefore(validFrom) || from.isAfter(validTo)) {
            throw new InvalidScholarshipsValueException("validFrom", "outside-discount-validity");
        }
        if (to == null || to.isAfter(validTo) || to.isBefore(validFrom)) {
            throw new InvalidScholarshipsValueException("validTo", "outside-discount-validity");
        }
    }

    /** Whether the discount covers the payment concept. */
    public boolean covers(UUID paymentConceptId) {
        return concepts.contains(paymentConceptId);
    }

    /** Whether the discount is approved and active and the day is inside its validity, both ends included. */
    public boolean appliesOn(LocalDate day) {
        Objects.requireNonNull(day, "day");
        return active && approval == ApprovalStatus.APPROVED && !day.isBefore(validFrom) && !day.isAfter(validTo);
    }

    private static Set<UUID> requireConcepts(Set<UUID> concepts) {
        if (concepts == null || concepts.isEmpty() || concepts.stream().anyMatch(Objects::isNull)) {
            throw new InvalidScholarshipsValueException("concepts", "invalid");
        }
        return Set.copyOf(concepts);
    }
}
