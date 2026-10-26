package com.confia.shared.security;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Registers the single transactional component, {@link TransactionRunner}, in the process that
 * imports it. It lives in {@code shared.security} because {@code
 * TransactionsOnlyInSharedSecurityTest} (ADR-0015, regla 7) confines every use of {@link
 * PlatformTransactionManager} to this package: the platform configuration of the administrative
 * process imports this class instead of naming that type itself.
 *
 * <p>Nothing here opens a transaction or a connection while the context starts.
 */
@Configuration(proxyBeanMethods = false)
public class TransactionRunnerConfiguration {

    /** The one transaction-aware proxy of the process's {@code DataSource} (BJ46). */
    @Bean
    TransactionalDataSource transactionalDataSource(DataSource dataSource) {
        return new TransactionalDataSource(dataSource);
    }

    @Bean
    TransactionRunner transactionRunner(PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new TransactionRunner(transactionManager, dataSource);
    }
}
