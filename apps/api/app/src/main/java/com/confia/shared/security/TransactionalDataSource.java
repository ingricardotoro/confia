package com.confia.shared.security;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The single transaction-aware proxy of a process's {@link DataSource}
 * (background-jobs-with-db-scheduler design.md decision 5; ADR-0015 rule 7). The {@code DSLContext}
 * of the process and, in the processes that schedule jobs, the db-scheduler client are built over
 * {@link #proxy()}, so both join the connection that {@link TransactionRunner} bound to the current
 * transaction: a task is written if and only if the business transaction commits. Having one
 * holder per process makes "one proxy per process" a checkable property instead of a convention
 * (scenario BJ46).
 *
 * <p>It is not itself a {@link DataSource}, so it never competes with Spring Boot's pool for the
 * single {@code DataSource} candidate that the auto-configurations require. It lives in {@code
 * shared.security} because that package is the only one allowed to reason about transactions.
 */
public final class TransactionalDataSource {

    private final DataSource proxy;

    /** Wraps {@code target} once; a target that already is the proxy is kept as it is. */
    public TransactionalDataSource(DataSource target) {
        Objects.requireNonNull(target, "target");
        this.proxy = target instanceof TransactionAwareDataSourceProxy alreadyProxied
                ? alreadyProxied : new TransactionAwareDataSourceProxy(target);
    }

    /** The one proxy of the process: always the same object. */
    public DataSource proxy() {
        return proxy;
    }

    /**
     * Fails unless a real transaction is open in the calling thread. Anything that must be written
     * together with the business data calls this before it touches the database, so that it can
     * never be written outside the transaction by mistake.
     *
     * @param operation what the caller was about to do, for the message
     * @throws IllegalStateException when there is no active transaction
     */
    public static void requireActiveTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("cannot " + operation
                    + " outside a transaction: open one with TransactionRunner first");
        }
    }
}
