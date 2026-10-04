package com.confia.shared.infrastructure;

import static confia.generated.jooq.tables.SharedAuditLog.SHARED_AUDIT_LOG;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogReader;
import com.confia.shared.audit.AuditRowSnapshot;
import confia.generated.jooq.tables.records.SharedAuditLogRecord;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;

/**
 * The single jOOQ adapter of {@link AuditLogReader} (design.md decision 11; ADR-0015 rule 4, R1:
 * jOOQ confined to {@code infrastructure}; rule 3, R2: {@code SharedAuditLog}'s generated table type
 * carries this module's own {@code Shared} prefix). {@code final}, with an explicit constructor
 * over {@link DSLContext} and no Spring annotation, the same pattern {@link
 * com.confia.organization.infrastructure.JooqInstitutionRepository} already established: no
 * bootstrap process registers this as a bean yet (the administrative process wires only the audit
 * writer, not this reader; web-edge-foundations design.md, decision 2).
 *
 * <p>Never returns {@code org.jooq.JSONB}, {@code java.time.OffsetDateTime} or any other jOOQ/JDK-
 * database type through the port: {@link #toSnapshot} converts every column explicitly, so {@link
 * AuditRowSnapshot} stays JDK-only (design.md decision 11).
 */
public final class JooqAuditLogReader implements AuditLogReader {

    private final DSLContext dsl;

    public JooqAuditLogReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<AuditRowSnapshot> pageOf(InstitutionId institutionId, long afterId, int pageSize) {
        return dsl.selectFrom(SHARED_AUDIT_LOG)
                .where(SHARED_AUDIT_LOG.INSTITUTION_ID.eq(institutionId.value()))
                .and(SHARED_AUDIT_LOG.ID.gt(afterId))
                .orderBy(SHARED_AUDIT_LOG.ID.asc())
                .limit(pageSize)
                .fetch(JooqAuditLogReader::toSnapshot);
    }

    private static AuditRowSnapshot toSnapshot(SharedAuditLogRecord row) {
        return new AuditRowSnapshot(row.getId(), row.getInstitutionId(),
                row.getOccurredAt().toInstant(), row.getActorId(), row.getActorKind(),
                row.getActorLabel(), row.getSourceIp(), row.getUserAgent(), row.getRequestId(),
                row.getTraceId(), row.getAction(), row.getEntityType(), row.getEntityId(),
                row.getOutcome(), jsonbText(row.getBeforeValue()), jsonbText(row.getAfterValue()),
                row.getReason(), row.getApproverId(), row.getPrevHash(), row.getRowHash());
    }

    /** {@code null} exactly when the {@code jsonb} column itself is SQL {@code NULL}. */
    private static String jsonbText(JSONB value) {
        return value == null ? null : value.data();
    }
}
