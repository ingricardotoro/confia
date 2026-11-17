package com.confia.students.infrastructure.wiring;

import com.confia.students.application.directory.EnrollmentRoster;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.infrastructure.JooqEnrollmentRoster;
import com.confia.students.infrastructure.JooqStudentDirectory;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The registration of {@code students} for the worker process (docs/cortes/charges-accrual.md; ADR-0016): the two read-only
 * directories that the accrual job consumes, the roster of enrollments and the student directory, and nothing else. It holds
 * no protector of the national identity document, no blind-index key and no use case: the worker has no column-encryption key.
 */
@Configuration(proxyBeanMethods = false)
public class StudentsWorkerConfiguration {

    @Bean
    StudentDirectory studentDirectory(DSLContext dsl) {
        return new JooqStudentDirectory(dsl);
    }

    @Bean
    EnrollmentRoster enrollmentRoster(DSLContext dsl) {
        return new JooqEnrollmentRoster(dsl);
    }
}
