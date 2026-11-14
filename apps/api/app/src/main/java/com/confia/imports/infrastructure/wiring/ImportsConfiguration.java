package com.confia.imports.infrastructure.wiring;

import com.confia.imports.application.ImportBatchRepository;
import com.confia.imports.application.ImportStudents;
import com.confia.imports.infrastructure.JooqImportBatchRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.importing.StudentImportGateway;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code imports} for the administrative process (docs/cortes/imports-students.md;
 * ADR-0024): the jOOQ adapter of the batches and the use case over it. The controller is registered by {@code
 * ImportsWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the audit writer and a clock,
 * which only the administrative process has, and the {@link StudentImportGateway} that {@code students} publishes.
 */
@Configuration(proxyBeanMethods = false)
public class ImportsConfiguration {

    @Bean
    ImportBatchRepository importBatchRepository(DSLContext dsl) {
        return new JooqImportBatchRepository(dsl);
    }

    @Bean
    ImportStudents importStudents(TransactionRunner transactionRunner, ImportBatchRepository batches,
            StudentImportGateway students, AuditLogWriter auditLogWriter, Clock clock) {
        return new ImportStudents(transactionRunner, batches, students, auditLogWriter, clock);
    }
}
