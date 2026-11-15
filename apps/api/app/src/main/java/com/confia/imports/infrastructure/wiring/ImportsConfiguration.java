package com.confia.imports.infrastructure.wiring;

import com.confia.guardians.application.importing.GuardianImportGateway;
import com.confia.guardians.application.importing.GuardianLinkImportGateway;
import com.confia.imports.application.ImportBatchRepository;
import com.confia.imports.application.ManageImports;
import com.confia.imports.application.ManageReconciliations;
import com.confia.imports.application.ReconciliationRepository;
import com.confia.imports.infrastructure.JooqImportBatchRepository;
import com.confia.imports.infrastructure.JooqReconciliationRepository;
import com.confia.organization.application.directory.SectionDirectory;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.EnrollmentCensus;
import com.confia.students.application.importing.EnrollmentImportGateway;
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
            EnrollmentImportGateway enrollments,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageImports(transactionRunner, batches, students, guardians, links, enrollments, auditLogWriter,
                clock);
    }

    @Bean
    ReconciliationRepository reconciliationRepository(DSLContext dsl) {
        return new JooqReconciliationRepository(dsl);
    }

    /** Signs the reconciliation of the migration, reading the year from {@code organization} and the census from {@code students}. */
    @Bean
    ManageReconciliations manageReconciliations(TransactionRunner transactionRunner, ImportBatchRepository batches,
            ReconciliationRepository reconciliations, SectionDirectory sections, EnrollmentCensus census,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageReconciliations(transactionRunner, batches, reconciliations, sections, census,
                auditLogWriter, clock);
    }
}
