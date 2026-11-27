package com.confia.scholarships.infrastructure.wiring;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.organization.application.directory.AcademicStructureDirectory;
import com.confia.scholarships.application.AssignmentDecisionRepository;
import com.confia.scholarships.application.AwardBenefitsService;
import com.confia.scholarships.application.AwardDecisionRepository;
import com.confia.scholarships.application.AwardRepository;
import com.confia.scholarships.application.AwardsInForceRepository;
import com.confia.scholarships.application.DecideAward;
import com.confia.scholarships.application.DecideDiscountAssignment;
import com.confia.scholarships.application.DecideDiscountChange;
import com.confia.scholarships.application.DiscountAssignmentRepository;
import com.confia.scholarships.application.DiscountDecisionRepository;
import com.confia.scholarships.application.DiscountBenefitsService;
import com.confia.scholarships.application.DiscountRepository;
import com.confia.scholarships.application.DiscountsInForceRepository;
import com.confia.scholarships.application.ManageDiscounts;
import com.confia.scholarships.application.ManageScholarships;
import com.confia.scholarships.application.RequestAward;
import com.confia.scholarships.application.RequestDiscountAssignment;
import com.confia.scholarships.application.ScholarshipRepository;
import com.confia.scholarships.application.ViewAwards;
import com.confia.scholarships.application.ViewDiscountAssignments;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.scholarships.infrastructure.JooqAssignmentDecisionRepository;
import com.confia.scholarships.infrastructure.JooqAwardDecisionRepository;
import com.confia.scholarships.infrastructure.JooqAwardRepository;
import com.confia.scholarships.infrastructure.JooqAwardsInForceRepository;
import com.confia.scholarships.infrastructure.JooqDiscountAssignmentRepository;
import com.confia.scholarships.infrastructure.JooqDiscountDecisionRepository;
import com.confia.scholarships.infrastructure.JooqDiscountRepository;
import com.confia.scholarships.infrastructure.JooqDiscountsInForceRepository;
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
    AwardsInForceRepository awardsInForceRepository(DSLContext dsl) {
        return new JooqAwardsInForceRepository(dsl);
    }

    /** What the approved awards offer on a charge, which the accrual of {@code charges} reads (preview, simulator). */
    @Bean
    AwardBenefits awardBenefits(AwardsInForceRepository awardsInForce) {
        return new AwardBenefitsService(awardsInForce);
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

    @Bean
    DiscountRepository discountRepository(DSLContext dsl) {
        return new JooqDiscountRepository(dsl);
    }

    @Bean
    DiscountAssignmentRepository discountAssignmentRepository(DSLContext dsl) {
        return new JooqDiscountAssignmentRepository(dsl);
    }

    @Bean
    AssignmentDecisionRepository assignmentDecisionRepository(DSLContext dsl) {
        return new JooqAssignmentDecisionRepository(dsl);
    }

    @Bean
    DiscountDecisionRepository discountDecisionRepository(DSLContext dsl) {
        return new JooqDiscountDecisionRepository(dsl);
    }

    @Bean
    DiscountsInForceRepository discountsInForceRepository(DSLContext dsl) {
        return new JooqDiscountsInForceRepository(dsl);
    }

    /** What the discounts in force offer on a charge, which the accrual of {@code charges} reads (preview, simulator). */
    @Bean
    DiscountBenefits discountBenefits(DiscountsInForceRepository discountsInForce) {
        return new DiscountBenefitsService(discountsInForce);
    }

    @Bean
    ManageDiscounts manageDiscounts(TransactionRunner transactionRunner, DiscountRepository discounts,
            DiscountAssignmentRepository assignments, PaymentConceptDirectory concepts, AuditLogWriter auditLogWriter,
            Clock clock) {
        return new ManageDiscounts(transactionRunner, discounts, assignments, concepts, auditLogWriter, clock);
    }

    @Bean
    DecideDiscountChange decideDiscountChange(TransactionRunner transactionRunner, DiscountRepository discounts,
            DiscountDecisionRepository decisions, DiscountAssignmentRepository assignments, PaymentConceptDirectory concepts,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new DecideDiscountChange(transactionRunner, discounts, decisions, assignments, concepts, auditLogWriter, clock);
    }

    @Bean
    RequestDiscountAssignment requestDiscountAssignment(TransactionRunner transactionRunner, DiscountRepository discounts,
            DiscountAssignmentRepository assignments, AssignmentDecisionRepository decisions, StudentDirectory students,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new RequestDiscountAssignment(transactionRunner, discounts, assignments, decisions, students, auditLogWriter,
                clock);
    }

    @Bean
    DecideDiscountAssignment decideDiscountAssignment(TransactionRunner transactionRunner, DiscountRepository discounts,
            DiscountAssignmentRepository assignments, AssignmentDecisionRepository decisions, StudentDirectory students,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new DecideDiscountAssignment(transactionRunner, discounts, assignments, decisions, students, auditLogWriter,
                clock);
    }

    @Bean
    ViewDiscountAssignments viewDiscountAssignments(TransactionRunner transactionRunner, DiscountRepository discounts,
            DiscountAssignmentRepository assignments, AssignmentDecisionRepository decisions, StudentDirectory students) {
        return new ViewDiscountAssignments(transactionRunner, discounts, assignments, decisions, students);
    }
}
