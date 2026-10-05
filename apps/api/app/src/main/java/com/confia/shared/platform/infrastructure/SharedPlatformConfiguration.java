package com.confia.shared.platform.infrastructure;

import com.confia.kernel.AesGcmCipher;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionMasterKey;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.crypto.DataEncryptionKeyRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.infrastructure.JooqDataEncryptionKeyRepository;
import com.confia.shared.infrastructure.JooqIdempotencyRecordStore;
import com.confia.shared.security.IdempotencyRecordStore;
import com.confia.shared.security.IdempotentExecutor;
import com.confia.shared.security.RequestPayloadHasher;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.security.TransactionRunnerConfiguration;
import java.time.Clock;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * The production wiring of the shared platform for the administrative process only
 * (web-edge-foundations design.md, decisions 1 and 2): the {@link DSLContext}, the single
 * transactional component, the clock, the audit and idempotency adapters and column encryption.
 * It is imported explicitly by {@code AdminApplication} (ADR-0024); the portal and the worker
 * never load it, and {@code ProcessBeanPolicy} lists this package as forbidden for both.
 *
 * <p>Nothing here opens a database connection while the context starts. The {@link DataSource}
 * is Spring Boot's Hikari pool, which connects lazily, and Flyway stays disabled in {@code
 * application.yml}: only the {@code migrate} profile ever migrates. A process pointed at a
 * database that is down therefore starts and reports the failure on the first use.
 *
 * <p>The column-encryption master key is a secret and is read with {@link
 * Environment#getProperty(String)}, never bound with {@code @ConfigurationProperties} or {@code
 * @Value}: Spring Boot's binding failure analyzer prints the rejected value, and a secret must
 * never reach a log (CLAUDE.md, regla 11). A missing or malformed key stops the process at
 * startup, with a message that names the property and never repeats its value.
 *
 * <p><b>Package.</b> The package ends in {@code infrastructure} on purpose: ArchUnit's {@code
 * JooqConfinedToInfrastructureTest} (ADR-0015 rule 4) forbids {@code org.jooq} anywhere else, and
 * this class declares a {@link DSLContext}. The design placed it in a package with no layer
 * segment; that cannot hold, and the deviation is recorded in {@code apply-progress.md}. For the
 * same reason the {@code TransactionRunner} bean lives in {@link TransactionRunnerConfiguration},
 * imported below: only {@code shared.security} may name {@code PlatformTransactionManager}.
 */
@Configuration(proxyBeanMethods = false)
@Import(TransactionRunnerConfiguration.class)
public class SharedPlatformConfiguration {

    /** The property the master key is read from (environment variable {@code
     * CONFIA_CRYPTO_COLUMNMASTERKEY}). */
    static final String MASTER_KEY_PROPERTY = "confia.crypto.column-master-key";

    @Bean
    DSLContext dslContext(DataSource dataSource) {
        DataSourceConnectionProvider connectionProvider =
                new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource));
        // jOOQ logs each statement with its bind values inlined at DEBUG; a document number or a
        // token must never reach a log (regla 11), so the statement logging is off in the
        // settings, whatever level any logger has.
        return DSL.using(new DefaultConfiguration()
                .set(connectionProvider)
                .set(SQLDialect.POSTGRES)
                .set(new Settings().withExecuteLogging(false)));
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AuditLogWriter auditLogWriter(DSLContext dsl) {
        return new JooqAuditLogWriter(dsl);
    }

    @Bean
    IdempotencyRecordStore idempotencyRecordStore(DSLContext dsl) {
        return new JooqIdempotencyRecordStore(dsl);
    }

    @Bean
    RequestPayloadHasher requestPayloadHasher() {
        return new RequestPayloadHasher();
    }

    @Bean
    IdempotentExecutor idempotentExecutor(TransactionRunner runner,
            IdempotencyRecordStore store, RequestPayloadHasher hasher, Clock clock,
            DataSource dataSource) {
        return new IdempotentExecutor(runner, store, hasher, clock, dataSource);
    }

    @Bean
    AesGcmCipher aesGcmCipher() {
        return new AesGcmCipher();
    }

    @Bean
    ColumnEncryptionMasterKey columnEncryptionMasterKey(Environment environment) {
        String configured = environment.getProperty(MASTER_KEY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("the property " + MASTER_KEY_PROPERTY
                    + " is required and was not set");
        }
        try {
            return ColumnEncryptionMasterKey.fromBase64(configured);
        } catch (IllegalArgumentException e) {
            // The message of ColumnEncryptionMasterKey states what is wrong and never the value;
            // the cause is not chained so no decoder detail can carry a fragment of the secret.
            throw new IllegalStateException("the property " + MASTER_KEY_PROPERTY
                    + " is not a valid master key: " + e.getMessage());
        }
    }

    @Bean
    DataEncryptionKeyRepository dataEncryptionKeyRepository(DSLContext dsl, AesGcmCipher cipher,
            ColumnEncryptionMasterKey masterKey) {
        return new JooqDataEncryptionKeyRepository(dsl, cipher, masterKey);
    }

    @Bean
    ColumnEncryptionService columnEncryptionService(DataEncryptionKeyRepository keys,
            AesGcmCipher cipher) {
        return new ColumnEncryptionService(keys, cipher);
    }
}
