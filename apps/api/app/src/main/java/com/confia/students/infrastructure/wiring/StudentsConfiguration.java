package com.confia.students.infrastructure.wiring;

import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.BlindIndexService;
import com.confia.shared.crypto.ColumnEncryptionMasterKey;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.ManageStudents;
import com.confia.students.application.NationalIdProtector;
import com.confia.students.application.StudentRepository;
import com.confia.students.infrastructure.ColumnEncryptionNationalIdProtector;
import com.confia.students.infrastructure.JooqStudentRepository;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code students} for the administrative process
 * (docs/cortes/students-registry.md; ADR-0024): the jOOQ adapter of the registry, the protector of the
 * national identity document over the shared column encryption, the blind-index service keyed from the
 * column-encryption master key, and the use case over them. The controller is registered by {@code
 * StudentsWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the audit writer,
 * the column encryption and a clock, which only the administrative process has.
 */
@Configuration(proxyBeanMethods = false)
public class StudentsConfiguration {

    @Bean
    StudentRepository studentRepository(DSLContext dsl) {
        return new JooqStudentRepository(dsl);
    }

    @Bean
    BlindIndexService blindIndexService(ColumnEncryptionMasterKey masterKey) {
        return new BlindIndexService(masterKey);
    }

    @Bean
    NationalIdProtector nationalIdProtector(ColumnEncryptionService encryption, BlindIndexService blindIndexes) {
        return new ColumnEncryptionNationalIdProtector(encryption, blindIndexes);
    }

    @Bean
    ManageStudents manageStudents(TransactionRunner transactionRunner, StudentRepository students,
            NationalIdProtector protector, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageStudents(transactionRunner, students, protector, auditLogWriter, clock);
    }
}
