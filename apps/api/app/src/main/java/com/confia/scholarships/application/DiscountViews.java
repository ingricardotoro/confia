package com.confia.scholarships.application;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptLabel;
import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.Discount;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the views of the discounts: the covered concepts, and the concepts of a waiting proposal, with their code and name. It runs
 * inside the transaction of the use case that asks.
 */
final class DiscountViews {

    private final PaymentConceptDirectory concepts;
    private final DiscountAssignmentRepository assignments;

    DiscountViews(PaymentConceptDirectory concepts, DiscountAssignmentRepository assignments) {
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
    }

    /** One discount; whether it is in use is asked of the database. */
    DiscountView one(InstitutionId institution, Discount discount) {
        return view(institution, discount, assignments.inUse(institution, Set.of(discount.id())).contains(discount.id()));
    }

    DiscountView view(InstitutionId institution, Discount discount, boolean inUse) {
        Map<UUID, PaymentConceptLabel> labels = labelsOf(institution, List.of(discount));
        return of(discount, labels, inUse);
    }

    /** A page of discounts: the labels and the discounts in use are read once each. */
    Page<DiscountView> page(InstitutionId institution, Page<Discount> found) {
        Set<UUID> ids = new HashSet<>();
        found.items().forEach(discount -> ids.add(discount.id()));
        Set<UUID> inUse = ids.isEmpty() ? Set.of() : assignments.inUse(institution, ids);
        Map<UUID, PaymentConceptLabel> labels = labelsOf(institution, found.items());
        return found.map(discount -> of(discount, labels, inUse.contains(discount.id())));
    }

    private static DiscountView of(Discount discount, Map<UUID, PaymentConceptLabel> labels, boolean inUse) {
        return DiscountView.of(discount, conceptViews(discount.concepts(), labels),
                discount.proposal() == null ? List.of() : conceptViews(discount.proposal().concepts(), labels), inUse);
    }

    private Map<UUID, PaymentConceptLabel> labelsOf(InstitutionId institution, Collection<Discount> found) {
        Set<UUID> ids = new HashSet<>();
        found.forEach(discount -> {
            ids.addAll(discount.concepts());
            if (discount.proposal() != null) {
                ids.addAll(discount.proposal().concepts());
            }
        });
        return ids.isEmpty() ? Map.of() : concepts.labels(institution, ids);
    }

    private static List<DiscountView.ConceptView> conceptViews(Set<UUID> ids, Map<UUID, PaymentConceptLabel> labels) {
        return ids.stream().map(labels::get).filter(Objects::nonNull)
                .map(label -> new DiscountView.ConceptView(label.id(), label.code(), label.name())).toList();
    }
}
