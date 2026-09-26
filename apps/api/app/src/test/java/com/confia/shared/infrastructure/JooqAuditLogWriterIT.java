package com.confia.shared.infrastructure;

import static confia.generated.jooq.tables.SharedAuditLog.SHARED_AUDIT_LOG;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import confia.generated.jooq.tables.records.SharedAuditLogRecord;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The first production writer of {@code shared_audit_log} (design.md §11, paso 14;
 * specs/identity/spec.md, "Puerto y adaptador de escritura de la bitácora de auditoría"). Proves
 * that {@link JooqAuditLogWriter#append} never computes {@code id}, {@code prev_hash} nor
 * {@code row_hash} itself: the chaining trigger ({@code
 * V3__chain_shared_audit_log.sql:shared_audit_log_chain()}) assigns all three, and this test never
 * sets any of them through {@link AuditEntry}, which has no field for any of the three.
 */
class JooqAuditLogWriterIT extends CommittingPostgresIntegrationTest {

    private static final String STAFF_ACCOUNT_ENTITY_TYPE = "identity.staff_account";

    private AuditLogWriter writer() {
        return new JooqAuditLogWriter(dsl);
    }

    @Test
    void theChainingTriggerAssignsIdPrevHashAndRowHashAndChainsToTheInstitutionsChain() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        AuditEntry first = entryFor(institutionId, "identity.login.succeeded", "success", null);
        AuditEntry second = entryFor(institutionId, "identity.login.failed", "denied",
                "{\"reason\":\"invalid-password\"}");

        transactionRunner().execute(contextOf(institutionId), () -> {
            writer().append(first);
            return null;
        });
        transactionRunner().execute(contextOf(institutionId), () -> {
            writer().append(second);
            return null;
        });

        // Read back inside a third, real transaction with the same security context: shared_audit_log
        // enforces row-level security (V2 migration), so a plain dsl.selectFrom outside any
        // TransactionRunner-opened transaction never sees app.institution_id set and reads back zero
        // rows by policy, not by the writer's own fault (AuditChainTriggerIT's own precedent).
        List<SharedAuditLogRecord> rows = transactionRunner().execute(contextOf(institutionId),
                () -> dsl.selectFrom(SHARED_AUDIT_LOG)
                        .where(SHARED_AUDIT_LOG.INSTITUTION_ID.eq(institutionId.value()))
                        .orderBy(SHARED_AUDIT_LOG.ID.asc())
                        .fetch());

        assertThat(rows).hasSize(2);
        SharedAuditLogRecord firstRow = rows.get(0);
        SharedAuditLogRecord secondRow = rows.get(1);

        assertThat(firstRow.getId()).isNotNull();
        assertThat(firstRow.getPrevHash())
                .as("the chaining trigger assigns the genesis prev_hash for the first row of a "
                        + "fresh institution chain")
                .isEqualTo(new byte[32]);
        assertThat(firstRow.getRowHash()).isNotNull();
        assertThat(firstRow.getActorLabel()).isEqualTo("maria.lopez@colegio.edu.hn");
        assertThat(firstRow.getAction()).isEqualTo("identity.login.succeeded");
        assertThat(firstRow.getOutcome()).isEqualTo("success");
        assertThat(firstRow.getAfterValue()).isNull();

        assertThat(secondRow.getId()).isEqualTo(firstRow.getId() + 1);
        assertThat(secondRow.getPrevHash())
                .as("the second row chains to the first row's own row_hash, never the genesis "
                        + "value again")
                .isEqualTo(firstRow.getRowHash());
        assertThat(secondRow.getRowHash()).isNotEqualTo(secondRow.getPrevHash());
        assertThat(secondRow.getOutcome()).isEqualTo("denied");
        assertThat(secondRow.getAfterValue().data()).isEqualTo("{\"reason\": \"invalid-password\"}");
    }

    private static AuditEntry entryFor(InstitutionId institutionId, String action, String outcome,
            String afterValue) {
        return new AuditEntry(institutionId.value(), UUID.randomUUID(), "staff",
                "maria.lopez@colegio.edu.hn", null, null, UUID.randomUUID(), null, action,
                STAFF_ACCOUNT_ENTITY_TYPE, "a".repeat(64), outcome, null, afterValue, null, null);
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
