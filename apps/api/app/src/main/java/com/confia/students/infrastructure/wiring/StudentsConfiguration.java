package com.confia.students.infrastructure.wiring;

import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.BlindIndexKeys;
import com.confia.shared.crypto.BlindIndexService;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.ManageStudents;
import com.confia.students.application.NationalIdProtector;
import com.confia.students.application.NationalIdReindexer;
import com.confia.students.application.ReindexNationalIds;
import com.confia.students.application.StudentRepository;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.infrastructure.ColumnEncryptionNationalIdProtector;
import com.confia.students.infrastructure.JooqStudentDirectory;
import com.confia.students.infrastructure.JooqStudentRepository;
import com.confia.students.infrastructure.ReindexNationalIdsRunner;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The public configuration of {@code students} for the administrative process
 * (docs/cortes/students-registry.md; ADR-0024): the jOOQ adapter of the registry, the protector of the
 * national identity document over the shared column encryption, the blind-index service with its own versioned
 * keys (not derived from the column-encryption master key), and the use case over them. The controller is registered by {@code
 * StudentsWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the audit writer,
 * the column encryption and a clock, which only the administrative process has.
 */
@Configuration(proxyBeanMethods = false)
public class StudentsConfiguration {

    @Bean
    StudentRepository studentRepository(DSLContext dsl) {
        return new JooqStudentRepository(dsl);
    }

    /** The read-only view of the students that {@code guardians} consumes (docs/cortes/guardians-registry.md). */
    @Bean
    StudentDirectory studentDirectory(DSLContext dsl) {
        return new JooqStudentDirectory(dsl);
    }

    /** The institutions whose blind indexes are recomputed at startup (empty: none). */
    static final String REINDEX_INSTITUTIONS_PROPERTY = "confia.students.reindex.institutions";

    /** The property that names the version that makes new blind indexes (environment variable {@code
     * CONFIA_CRYPTO_BLINDINDEX_CURRENTVERSION}). */
    static final String CURRENT_VERSION_PROPERTY = "confia.crypto.blind-index.current-version";

    /** The prefix of the key of each version, {@code confia.crypto.blind-index.keys.v1} (environment variable
     * {@code CONFIA_CRYPTO_BLINDINDEX_KEYS_V1}). */
    static final String KEYS_PROPERTY = "confia.crypto.blind-index.keys";

    /**
     * Reads the dedicated blind-index keys (docs/03-seguridad.md section 7.3) and fails at startup when
     * they are absent or malformed. The message names the property and what is wrong, never a value, and
     * no cause is chained.
     */
    @Bean
    BlindIndexKeys blindIndexKeys(Environment environment) {
        return blindIndexKeysFrom(environment);
    }

    static BlindIndexKeys blindIndexKeysFrom(Environment environment) {
        String current = environment.getProperty(CURRENT_VERSION_PROPERTY);
        if (current == null || current.isBlank()) {
            throw new IllegalStateException("the property " + CURRENT_VERSION_PROPERTY
                    + " is required and was not set");
        }
        Map<String, String> configured = Binder.get(environment)
                .bind(KEYS_PROPERTY, Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        if (configured.isEmpty()) {
            throw new IllegalStateException("the properties " + KEYS_PROPERTY
                    + ".<version> are required and none was set");
        }
        try {
            return BlindIndexKeys.of(current.strip(), configured);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("the blind-index configuration is not valid: " + e.getMessage());
        }
    }

    @Bean
    BlindIndexService blindIndexService(BlindIndexKeys keys) {
        return new BlindIndexService(keys);
    }

    /** One adapter, two ports: it protects a document for storing and recomputes its index after a rotation. */
    @Bean
    ColumnEncryptionNationalIdProtector nationalIdProtector(ColumnEncryptionService encryption,
            BlindIndexService blindIndexes) {
        return new ColumnEncryptionNationalIdProtector(encryption, blindIndexes);
    }

    @Bean
    ReindexNationalIds reindexNationalIds(TransactionRunner transactionRunner, StudentRepository students,
            NationalIdReindexer reindexer, AuditLogWriter auditLogWriter) {
        return new ReindexNationalIds(transactionRunner, students, reindexer, auditLogWriter);
    }

    /**
     * The documented command of a blind-index key rotation: with {@code confia.students.reindex.institutions}
     * set (comma separated institution identifiers) the process recomputes their stored indexes at startup.
     */
    @Bean
    @ConditionalOnProperty(name = REINDEX_INSTITUTIONS_PROPERTY)
    ReindexNationalIdsRunner reindexNationalIdsRunner(ReindexNationalIds reindex, Environment environment) {
        List<UUID> institutions = Binder.get(environment)
                .bind(REINDEX_INSTITUTIONS_PROPERTY, Bindable.listOf(UUID.class)).orElse(List.of());
        return new ReindexNationalIdsRunner(reindex, institutions);
    }

    @Bean
    ManageStudents manageStudents(TransactionRunner transactionRunner, StudentRepository students,
            NationalIdProtector protector, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageStudents(transactionRunner, students, protector, auditLogWriter, clock);
    }
}
