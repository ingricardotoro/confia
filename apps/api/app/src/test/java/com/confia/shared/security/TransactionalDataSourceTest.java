package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scenario BJ46 and design.md decision 5 of background-jobs-with-db-scheduler: the process holds
 * one transaction-aware proxy of its {@code DataSource}, never wrapped twice, and anything that
 * must run inside a business transaction can ask for it ({@link
 * TransactionalDataSource#requireActiveTransaction(String)}).
 */
class TransactionalDataSourceTest {

    private static DataSource target() {
        // Never connects: nothing in this test opens a connection.
        return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/never");
    }

    @Test
    void itWrapsTheTargetOnceAndAlwaysReturnsTheSameProxy() {
        DataSource target = target();
        TransactionalDataSource holder = new TransactionalDataSource(target);

        assertThat(holder.proxy()).isInstanceOf(TransactionAwareDataSourceProxy.class);
        assertThat(((TransactionAwareDataSourceProxy) holder.proxy()).getTargetDataSource())
                .isSameAs(target);
        assertThat(holder.proxy()).isSameAs(holder.proxy());
    }

    @Test
    void aTargetThatIsAlreadyTheProxyIsNeverWrappedASecondTime() {
        TransactionAwareDataSourceProxy alreadyProxied =
                new TransactionAwareDataSourceProxy(target());

        TransactionalDataSource holder = new TransactionalDataSource(alreadyProxied);

        assertThat(holder.proxy()).isSameAs(alreadyProxied);
    }

    @Test
    void aMissingTargetIsRefused() {
        assertThatThrownBy(() -> new TransactionalDataSource(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("target");
    }

    @Test
    void outsideATransactionRequireActiveTransactionThrowsAndNamesTheOperation() {
        assertThatThrownBy(() -> TransactionalDataSource.requireActiveTransaction("schedule a job"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schedule a job").hasMessageContaining("transaction");
    }

    @Test
    void insideATransactionRequireActiveTransactionReturns() {
        TransactionTemplate template = new TransactionTemplate(new NoOpTransactionManager());

        assertThatCode(() -> template.executeWithoutResult(status ->
                TransactionalDataSource.requireActiveTransaction("schedule a job")))
                .doesNotThrowAnyException();
    }

    /** A manager that begins and ends nothing but keeps the synchronization state Spring reads. */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
