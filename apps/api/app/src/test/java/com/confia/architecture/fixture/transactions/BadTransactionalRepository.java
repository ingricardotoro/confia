package com.confia.architecture.fixture.transactions;

import org.springframework.transaction.annotation.Transactional;

/**
 * Permanent negative fixture for {@code TransactionsOnlyInSharedSecurityTest} (task 3.3, rule
 * R3): a repository-shaped class outside {@code shared.security} that opens its own transaction,
 * which ADR-0015 rule 7 confines to the single transactional component of {@code shared.security}
 * (design.md decision 9, rule R3). {@code shared.security} does not exist yet in this part of the
 * change; this rule is a preventive guard so the first adapter never opens its own transaction
 * before that component exists.
 */
@Transactional
public final class BadTransactionalRepository {

    public void save() {
        // Deliberately empty: the annotation alone is the violation this fixture exists to prove.
    }
}
