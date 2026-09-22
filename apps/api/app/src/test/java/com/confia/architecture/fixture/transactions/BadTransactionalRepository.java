package com.confia.architecture.fixture.transactions;

import org.springframework.transaction.annotation.Transactional;

/**
 * Permanent negative fixture for {@code TransactionsOnlyInSharedSecurityTest} (rule R3): a
 * repository-shaped class outside {@code shared.security} that opens its own transaction, which
 * ADR-0015 rule 7 confines to the single transactional component of {@code shared.security}
 * ({@code com.confia.shared.security.TransactionRunner}, F0 change 5 part B). No longer a
 * preventive guard over an empty package: the real component exists, and this fixture is what
 * proves the negative rule still rejects a transaction opened anywhere else.
 */
@Transactional
public final class BadTransactionalRepository {

    public void save() {
        // Deliberately empty: the annotation alone is the violation this fixture exists to prove.
    }
}
