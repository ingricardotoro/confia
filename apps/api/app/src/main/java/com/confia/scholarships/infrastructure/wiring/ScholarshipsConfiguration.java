package com.confia.scholarships.infrastructure.wiring;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.organization.application.directory.AcademicStructureDirectory;
import com.confia.scholarships.application.AwardDecisionRepository;
import com.confia.scholarships.application.AwardRepository;
import com.confia.scholarships.application.DecideAward;
import com.confia.scholarships.application.ManageScholarships;
import com.confia.scholarships.application.RequestAward;
import com.confia.scholarships.application.ScholarshipRepository;
import com.confia.scholarships.application.ViewAwards;
import com.confia.scholarships.infrastructure.JooqAwardDecisionRepository;
import com.confia.scholarships.infrastructure.JooqAwardRepository;
import com.confia.scholarships.infrastructure.JooqScholarshipRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code scholarships} for the administrative process (docs/cortes/scholarships-awards.md; ADR-0024):
 * the jOOQ adapters of the scholarships, their awards and the decisions on them, and the use cases over them. The controllers are
 * registered by {@code ScholarshipsWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the audit writer
 * and a clock, which only the administrative process has, and the public APIs that {@code catalog}, {@code students} and {@code
 * organization} publish. The portal and the worker never load it.
 */
@Configuration(proxyBeanMethods = false)
public class ScholarshipsConfiguration {

    @Bean
    ScholarshipRepository scholarshipRepository(DSLContext dsl) {
        return new JooqScholarshipRepository(dsl);
    }

    @Bean
    AwardRepository awardRepository(DSLContext dsl) {
        return new JooqAwardRepository(dsl);
    }

    @Bean
    AwardDecisionRepository awardDecisionRepository(DSLContext dsl) {
        return new JooqAwardDecisionRepository(dsl);
    }

    @Bean
    ManageScholarships manageScholarships(TransactionRunner transactionRunner, ScholarshipRepository scholarships,
            AwardRepository awards, PaymentConceptDirectory concepts, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageScholarships(transactionRunner, scholarships, awards, concepts, auditLogWriter, clock);
    }

    @Bean
    RequestAward requestAward(TransactionRunner transactionRunner, ScholarshipRepository scholarships, AwardRepository awards,
            AwardDecisionRepository decisions, StudentDirectory students, AcademicStructureDirectory academicStructure,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new RequestAward(transactionRunner, scholarships, awards, decisions, students, academicStructure,
                auditLogWriter, clock);
    }

    @Bean
    DecideAward decideAward(TransactionRunner transactionRunner, ScholarshipRepository scholarships, AwardRepository awards,
            AwardDecisionRepository decisions, StudentDirectory students, AuditLogWriter auditLogWriter, Clock clock) {
        return new DecideAward(transactionRunner, scholarships, awards, decisions, students, auditLogWriter, clock);
    }

    @Bean
    ViewAwards viewAwards(TransactionRunner transactionRunner, ScholarshipRepository scholarships, AwardRepository awards,
            AwardDecisionRepository decisions, StudentDirectory students) {
        return new ViewAwards(transactionRunner, scholarships, awards, decisions, students);
    }
}
