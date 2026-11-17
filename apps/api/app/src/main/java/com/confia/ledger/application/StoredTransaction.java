package com.confia.ledger.application;

import com.confia.ledger.domain.LedgerTransaction;
import java.time.Instant;

/** A transaction as stored: the domain object, the hash of its content and the instant the database posted it. */
public record StoredTransaction(LedgerTransaction transaction, String payloadHash, Instant postedAt) {
}
