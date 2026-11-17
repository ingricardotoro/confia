package com.confia.ledger.application;

import com.confia.kernel.Money;
import com.confia.ledger.domain.EntrySide;
import com.confia.ledger.domain.TransactionKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One line of the history of a student account. */
public record EntryView(UUID transactionId, TransactionKind kind, LocalDate effectiveOn, Instant postedAt,
        int lineNumber, UUID accountId, EntrySide side, Money amount, String sourceModule,
        UUID reversesTransactionId) {
}
