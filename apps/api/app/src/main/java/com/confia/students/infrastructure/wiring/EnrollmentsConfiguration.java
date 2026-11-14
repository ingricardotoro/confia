package com.confia.students.infrastructure.wiring;

import com.confia.organization.application.directory.SectionDirectory;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.EnrollmentRepository;
import com.confia.students.application.ManageEnrollments;
import com.confia.students.application.StudentRepository;
import com.confia.students.infrastructure.JooqEnrollmentRepository;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the enrollments for the administrative process (docs/cortes/enrollments.md;
 * ADR-0024): the jOOQ adapter of the enrollments and the use case over it, the registry of students and the
 * {@link SectionDirectory} that {@code organization} publishes. The controller is registered by {@code
 * EnrollmentsWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the audit writer
 * and a clock, which only the administrative process has, and the beans of {@code StudentsConfiguration} and
 * of the academic structure of {@code organization}.
 */
@Configuration(proxyBeanMethods = false)
public class EnrollmentsConfiguration {

    @Bean
    EnrollmentRepository enrollmentRepository(DSLContext dsl) {
        return new JooqEnrollmentRepository(dsl);
    }

    @Bean
    ManageEnrollments manageEnrollments(TransactionRunner transactionRunner, EnrollmentRepository enrollments,
            StudentRepository students, SectionDirectory sections, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageEnrollments(transactionRunner, enrollments, students, sections, auditLogWriter, clock);
    }
}
