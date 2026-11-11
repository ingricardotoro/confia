package com.confia.organization.infrastructure.wiring;

import com.confia.organization.application.AcademicYearRepository;
import com.confia.organization.application.GradeRepository;
import com.confia.organization.application.ManageAcademicYears;
import com.confia.organization.application.ManageGrades;
import com.confia.organization.application.ManageModalities;
import com.confia.organization.application.ManageSections;
import com.confia.organization.application.ModalityRepository;
import com.confia.organization.application.SectionRepository;
import com.confia.organization.infrastructure.JooqAcademicYearRepository;
import com.confia.organization.infrastructure.JooqGradeRepository;
import com.confia.organization.infrastructure.JooqModalityRepository;
import com.confia.organization.infrastructure.JooqSectionRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the academic structure of {@code organization} for the administrative
 * process (docs/cortes/organization-academic-structure.md; ADR-0024): the four jOOQ adapters (years,
 * modalities, grades and sections) and the four use cases over them. Their controllers are registered
 * by {@code OrganizationWebConfiguration}. It needs a {@link DSLContext}, the transactional component,
 * the audit writer and a clock, which only the administrative process has.
 */
@Configuration(proxyBeanMethods = false)
public class AcademicStructureConfiguration {

    @Bean
    AcademicYearRepository academicYearRepository(DSLContext dsl) {
        return new JooqAcademicYearRepository(dsl);
    }

    @Bean
    ModalityRepository modalityRepository(DSLContext dsl) {
        return new JooqModalityRepository(dsl);
    }

    @Bean
    GradeRepository gradeRepository(DSLContext dsl) {
        return new JooqGradeRepository(dsl);
    }

    @Bean
    SectionRepository sectionRepository(DSLContext dsl) {
        return new JooqSectionRepository(dsl);
    }

    @Bean
    ManageAcademicYears manageAcademicYears(TransactionRunner transactionRunner,
            AcademicYearRepository years, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageAcademicYears(transactionRunner, years, auditLogWriter, clock);
    }

    @Bean
    ManageModalities manageModalities(TransactionRunner transactionRunner,
            ModalityRepository modalities, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageModalities(transactionRunner, modalities, auditLogWriter, clock);
    }

    @Bean
    ManageGrades manageGrades(TransactionRunner transactionRunner, GradeRepository grades,
            ModalityRepository modalities, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageGrades(transactionRunner, grades, modalities, auditLogWriter, clock);
    }

    @Bean
    ManageSections manageSections(TransactionRunner transactionRunner, SectionRepository sections,
            AcademicYearRepository years, GradeRepository grades, AuditLogWriter auditLogWriter,
            Clock clock) {
        return new ManageSections(transactionRunner, sections, years, grades, auditLogWriter, clock);
    }
}
