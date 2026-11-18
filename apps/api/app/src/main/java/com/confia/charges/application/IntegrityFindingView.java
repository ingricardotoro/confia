package com.confia.charges.application;

import com.confia.charges.domain.IntegrityFinding;
import com.confia.kernel.Money;
import java.util.UUID;

/**
 * One confirmed discrepancy as the web layer may see it: the kind by name, the charge and the transaction it is about, and
 * what the records of the charges say against what the ledger holds. Each is {@code null} when the kind is not about it.
 */
public record IntegrityFindingView(String kind, UUID chargeId, UUID ledgerTransactionId, Money expected, Money actual) {

    static IntegrityFindingView of(IntegrityFinding finding) {
        return new IntegrityFindingView(finding.kind().name(), finding.chargeId(), finding.ledgerTransactionId(),
                finding.expected(), finding.actual());
    }
}
