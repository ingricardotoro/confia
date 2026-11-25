package com.confia.scholarships.domain;

import com.confia.kernel.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A scholarship of the institution (docs/02 section 3.3): a unique code, a name, a benefit (a percentage or a fixed amount), the
 * payment concepts it covers (one or more) and an optional cap per period in HNL. It is deactivated and reactivated, never deleted. An
 * inactive scholarship takes no new award and stops applying to the charges that are issued from then on. Once it has an award that
 * was approved, the type and the value of its benefit never change: another scholarship is created, so a charge issued with it is
 * always explained with the value it had. The same holds for its scope: with an award that was approved the cap per period only goes
 * down (it is never raised nor removed) and the covered concepts are only removed (never added); widening a scholarship is a new
 * scholarship. Editing asks for the version the editor saw.
 */
public record Scholarship(UUID id, String code, String name, Benefit benefit, Money periodCap, Set<UUID> concepts, boolean active,
        long version, UUID createdBy, Instant createdAt, Instant updatedAt) {

    public Scholarship {
        Objects.requireNonNull(id, "id");
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
        if (periodCap != null && !periodCap.isPositive()) {
            throw new InvalidScholarshipsValueException("periodCap", "invalid");
        }
    }

    public static Scholarship create(UUID id, String code, String name, Benefit benefit, Money periodCap, Set<UUID> concepts,
            UUID createdBy, Instant now) {
        return new Scholarship(id, code, name, benefit, periodCap, concepts, true, 0, createdBy, now, now);
    }

    /**
     * The same scholarship with a new name, benefit, cap and set of covered concepts and the version advanced; the code and the
     * active flag stay.
     *
     * @param expectedVersion the version the editor saw
     * @param hasApprovedAwards whether an award of this scholarship was ever approved
     * @throws StaleVersionException when the version is not the current one
     * @throws ScholarshipBenefitLockedException when the benefit changes and an award was approved
     * @throws ScholarshipScopeLockedException when an award was approved and the cap is raised or removed, or a concept is added
     */
    public Scholarship edit(long expectedVersion, String newName, Benefit newBenefit, Money newCap, Set<UUID> newConcepts,
            boolean hasApprovedAwards, Instant now) {
        if (expectedVersion != version) {
            throw new StaleVersionException();
        }
        if (hasApprovedAwards && !benefit.sameAs(Objects.requireNonNull(newBenefit, "newBenefit"))) {
            throw new ScholarshipBenefitLockedException();
        }
        Scholarship edited = new Scholarship(id, code, newName, newBenefit, newCap, newConcepts, active, version + 1, createdBy,
                createdAt, now);
        if (hasApprovedAwards && widensScope(edited)) {
            throw new ScholarshipScopeLockedException();
        }
        return edited;
    }

    /** Whether {@code edited} raises or removes the cap, or covers a concept this scholarship did not. */
    private boolean widensScope(Scholarship edited) {
        boolean capWidened = periodCap != null && (edited.periodCap == null || edited.periodCap.isGreaterThan(periodCap));
        return capWidened || !concepts.containsAll(edited.concepts);
    }

    /** The scholarship active or inactive; the same one when it already is, so the version only moves with a real change. */
    public Scholarship withActive(boolean newActive, Instant now) {
        if (newActive == active) {
            return this;
        }
        return new Scholarship(id, code, name, benefit, periodCap, concepts, newActive, version + 1, createdBy, createdAt, now);
    }

    /** @throws InactiveReferenceException when the scholarship is inactive: it takes no new award */
    public void requireActive() {
        if (!active) {
            throw new InactiveReferenceException();
        }
    }

    /** Whether the scholarship covers the payment concept. */
    public boolean covers(UUID paymentConceptId) {
        return concepts.contains(paymentConceptId);
    }

    private static Set<UUID> requireConcepts(Set<UUID> concepts) {
        if (concepts == null || concepts.isEmpty() || concepts.stream().anyMatch(Objects::isNull)) {
            throw new InvalidScholarshipsValueException("concepts", "invalid");
        }
        return Set.copyOf(concepts);
    }
}
