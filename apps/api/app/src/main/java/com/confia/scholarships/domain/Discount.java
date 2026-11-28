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
 * is in use (an assignment of it was approved) the type and the value of its benefit never change, its validity only shortens and its
 * covered concepts are only removed: widening a discount is a new discount, so a charge issued with it is always explained with what
 * it was. Editing asks for the version the editor saw.
 */
public record Discount(UUID id, String code, String name, DiscountKind kind, Benefit benefit, LocalDate validFrom,
        LocalDate validTo, Set<UUID> concepts, boolean stackable, boolean active, long version, UUID createdBy,
        Instant createdAt, Instant updatedAt) {

    private static final LocalDate WINDOW_START = LocalDate.of(2000, 1, 1);
    private static final LocalDate WINDOW_END = LocalDate.of(2100, 12, 31);

    public Discount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(benefit, "benefit");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
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

    public static Discount create(UUID id, String code, String name, DiscountKind kind, Benefit benefit, LocalDate validFrom,
            LocalDate validTo, Set<UUID> concepts, boolean stackable, UUID createdBy, Instant now) {
        return new Discount(id, code, name, kind, benefit, validFrom, validTo, concepts, stackable, true, 0, createdBy, now, now);
    }

    /**
     * The same discount with a new name, benefit, validity, stackable flag and set of covered concepts and the version advanced; the
     * code, the kind and the active flag stay.
     *
     * @param expectedVersion the version the editor saw
     * @param inUse whether an assignment of this discount was ever approved
     * @throws StaleVersionException when the version is not the current one
     * @throws DiscountBenefitLockedException when the benefit changes and the discount is in use
     * @throws DiscountScopeLockedException when it is in use and the validity widens or a concept is added
     */
    public Discount edit(long expectedVersion, String newName, Benefit newBenefit, LocalDate newFrom, LocalDate newTo,
            Set<UUID> newConcepts, boolean newStackable, boolean inUse, Instant now) {
        if (expectedVersion != version) {
            throw new StaleVersionException();
        }
        if (inUse && !benefit.sameAs(Objects.requireNonNull(newBenefit, "newBenefit"))) {
            throw new DiscountBenefitLockedException();
        }
        Discount edited = new Discount(id, code, newName, kind, newBenefit, newFrom, newTo, newConcepts, newStackable, active,
                version + 1, createdBy, createdAt, now);
        if (inUse && widensScope(edited)) {
            throw new DiscountScopeLockedException();
        }
        return edited;
    }

    /** Whether {@code edited} starts earlier, ends later, or covers a concept this discount did not. */
    private boolean widensScope(Discount edited) {
        return edited.validFrom.isBefore(validFrom) || edited.validTo.isAfter(validTo) || !concepts.containsAll(edited.concepts);
    }

    /** The discount active or inactive; the same one when it already is, so the version only moves with a real change. */
    public Discount withActive(boolean newActive, Instant now) {
        if (newActive == active) {
            return this;
        }
        return new Discount(id, code, name, kind, benefit, validFrom, validTo, concepts, stackable, newActive, version + 1,
                createdBy, createdAt, now);
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

    /** Whether the discount is active and the day is inside its validity, both ends included. */
    public boolean appliesOn(LocalDate day) {
        Objects.requireNonNull(day, "day");
        return active && !day.isBefore(validFrom) && !day.isAfter(validTo);
    }

    private static Set<UUID> requireConcepts(Set<UUID> concepts) {
        if (concepts == null || concepts.isEmpty() || concepts.stream().anyMatch(Objects::isNull)) {
            throw new InvalidScholarshipsValueException("concepts", "invalid");
        }
        return Set.copyOf(concepts);
    }
}
