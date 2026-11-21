package com.confia.charges.application;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptLabel;
import com.confia.charges.application.api.PayableCharges;
import com.confia.charges.domain.PostingAccountMap;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The implementation of {@link PayableCharges}: it translates the charges and the posting map of this module to the plain types
 * of the boundary, inside the transaction of the caller. It adds no rule of its own and writes nothing.
 */
public final class PayableChargesService implements PayableCharges {

    private final ChargeRepository charges;
    private final PostingAccountRepository postingAccounts;
    private final PaymentConceptDirectory concepts;

    public PayableChargesService(ChargeRepository charges, PostingAccountRepository postingAccounts,
            PaymentConceptDirectory concepts) {
        this.charges = Objects.requireNonNull(charges, "charges");
        this.postingAccounts = Objects.requireNonNull(postingAccounts, "postingAccounts");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
    }

    @Override
    public Optional<UUID> receivableAccount(InstitutionId institutionId) {
        return new PostingAccountMap(postingAccounts.list(institutionId)).receivable();
    }

    @Override
    public Map<UUID, ChargeLabel> chargeLabels(InstitutionId institutionId, Collection<UUID> chargeIds) {
        List<ChargeRepository.ChargeLabelRow> rows = charges.findLabels(institutionId, chargeIds);
        Set<UUID> conceptIds = new HashSet<>();
        rows.forEach(row -> conceptIds.add(row.paymentConceptId()));
        Map<UUID, PaymentConceptLabel> labels = concepts.labels(institutionId, conceptIds);
        Map<UUID, ChargeLabel> result = new java.util.HashMap<>();
        for (ChargeRepository.ChargeLabelRow row : rows) {
            PaymentConceptLabel label = labels.get(row.paymentConceptId());
            result.put(row.id(), new ChargeLabel(row.id(), row.paymentConceptId(), label == null ? null : label.code(),
                    label == null ? null : label.name(), row.periodLabel(), row.dueOn()));
        }
        return result;
    }

    @Override
    public List<ActiveCharge> activeCharges(InstitutionId institutionId, UUID studentId, CurrencyCode currency) {
        List<ChargeRepository.ChargeForPayment> found = charges.findActiveByStudent(institutionId, studentId, currency);
        Set<UUID> conceptIds = new HashSet<>();
        found.forEach(charge -> conceptIds.add(charge.paymentConceptId()));
        Map<UUID, PaymentConceptLabel> labels = concepts.labels(institutionId, conceptIds);
        return found.stream().map(charge -> {
            PaymentConceptLabel label = labels.get(charge.paymentConceptId());
            return new ActiveCharge(charge.id(), charge.paymentConceptId(), label == null ? null : label.code(),
                    charge.periodStart(), charge.dueOn(), charge.total());
        }).toList();
    }
}
