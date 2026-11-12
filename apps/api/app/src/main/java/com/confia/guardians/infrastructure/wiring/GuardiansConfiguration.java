package com.confia.guardians.infrastructure.wiring;

import com.confia.guardians.application.GuardianLinkRepository;
import com.confia.guardians.application.GuardianRepository;
import com.confia.guardians.application.GuardianSecretsProtector;
import com.confia.guardians.application.ManageGuardianLinks;
import com.confia.guardians.application.ManageGuardians;
import com.confia.guardians.infrastructure.ColumnEncryptionGuardianSecretsProtector;
import com.confia.guardians.infrastructure.JooqGuardianLinkRepository;
import com.confia.guardians.infrastructure.JooqGuardianRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.BlindIndexService;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code guardians} for the administrative process (docs/cortes/guardians-registry.md;
 * ADR-0024): the jOOQ adapters of the guardians and of their links, the protector of the national identity document
 * and the RTN over the shared column encryption, and the two use cases over them. The controllers are registered by
 * {@code GuardiansWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the audit writer, the
 * column encryption and a clock, which only the administrative process has, the {@link BlindIndexService} that {@code
 * StudentsConfiguration} declares from the versioned blind-index keys, and the {@link StudentDirectory} that
 * {@code students} publishes.
 */
@Configuration(proxyBeanMethods = false)
public class GuardiansConfiguration {

    @Bean
    GuardianRepository guardianRepository(DSLContext dsl) {
        return new JooqGuardianRepository(dsl);
    }

    @Bean
    GuardianLinkRepository guardianLinkRepository(DSLContext dsl) {
        return new JooqGuardianLinkRepository(dsl);
    }

    @Bean
    GuardianSecretsProtector guardianSecretsProtector(ColumnEncryptionService encryption,
            BlindIndexService blindIndexes) {
        return new ColumnEncryptionGuardianSecretsProtector(encryption, blindIndexes);
    }

    @Bean
    ManageGuardians manageGuardians(TransactionRunner transactionRunner, GuardianRepository guardians,
            GuardianLinkRepository links, StudentDirectory students, GuardianSecretsProtector protector,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageGuardians(transactionRunner, guardians, links, students, protector, auditLogWriter, clock);
    }

    @Bean
    ManageGuardianLinks manageGuardianLinks(TransactionRunner transactionRunner, GuardianRepository guardians,
            GuardianLinkRepository links, StudentDirectory students, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageGuardianLinks(transactionRunner, guardians, links, students, auditLogWriter, clock);
    }
}
