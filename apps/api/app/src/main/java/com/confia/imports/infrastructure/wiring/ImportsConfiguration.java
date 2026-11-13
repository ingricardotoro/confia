package com.confia.imports.infrastructure.wiring;

import com.confia.guardians.application.importing.GuardianImportGateway;
import com.confia.guardians.application.importing.GuardianLinkImportGateway;
import com.confia.imports.application.ImportBatchRepository;
import com.confia.imports.application.ManageImports;
import com.confia.imports.infrastructure.JooqImportBatchRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.importing.StudentImportGateway;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code imports} for the administrative process (docs/cortes/imports-students.md,
 * docs/cortes/imports-guardians.md; ADR-0024): the jOOQ adapter of the batches and the use case over it. The controller
 * is registered by {@code ImportsWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the
 * audit writer and a clock, which only the administrative process has, and the ports that {@code students} and {@code
 * guardians} publish.
 */
@Configuration(proxyBeanMethods = false)
public class ImportsConfiguration {

    @Bean
    ImportBatchRepository importBatchRepository(DSLContext dsl) {
        return new JooqImportBatchRepository(dsl);
    }

    @Bean
    ManageImports manageImports(TransactionRunner transactionRunner, ImportBatchRepository batches,
            StudentImportGateway students, GuardianImportGateway guardians, GuardianLinkImportGateway links,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageImports(transactionRunner, batches, students, guardians, links, auditLogWriter, clock);
    }
}
