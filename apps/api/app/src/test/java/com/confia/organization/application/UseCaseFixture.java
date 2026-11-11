package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The collaborators every unit test of a use case of the academic structure needs, in one place. */
abstract class UseCaseFixture {

    static final Instant NOW = Instant.parse("2026-03-01T15:00:00Z");

    final List<String> transactionCalls = new ArrayList<>();
    final List<AuditEntry> audit = new ArrayList<>();
    final InMemoryAcademicStructure store = new InMemoryAcademicStructure();
    final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls);
    final AuditLogWriter auditWriter = audit::add;
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final UUID institutionUuid = UUID.randomUUID();
    final InstitutionId institution = new InstitutionId(institutionUuid);
    final AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), institutionUuid,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW,
            NOW.plusSeconds(900));
    final String requestId = UUID.randomUUID().toString();

    /** The write and its audit entry were committed together. */
    boolean committed() {
        return transactionCalls.contains("commit") && !transactionCalls.contains("rollback");
    }

    boolean rolledBack() {
        return transactionCalls.contains("rollback") && !transactionCalls.contains("commit");
    }
}
