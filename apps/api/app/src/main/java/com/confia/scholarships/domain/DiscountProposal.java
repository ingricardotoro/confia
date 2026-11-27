package com.confia.scholarships.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The terms that a person proposed for an approved campaign discount, which become the terms in force only when a different person
 * approves them: a benefit of another type or a greater value, a validity that starts earlier or ends later, a concept more, the
 * stackable flag turned on, or the reactivation of an inactive discount. The terms in force do not change while it waits.
 *
 * @param reactivate whether the proposal also turns the discount on
 * @param requestedBy the person who proposed it, who never approves it
 */
public record DiscountProposal(String name, Benefit benefit, LocalDate validFrom, LocalDate validTo, boolean stackable,
        boolean reactivate, Set<UUID> concepts, UUID requestedBy, Instant requestedAt) {

    public DiscountProposal {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(benefit, "benefit");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validTo, "validTo");
        Objects.requireNonNull(concepts, "concepts");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(requestedAt, "requestedAt");
        concepts = Set.copyOf(concepts);
    }
}
