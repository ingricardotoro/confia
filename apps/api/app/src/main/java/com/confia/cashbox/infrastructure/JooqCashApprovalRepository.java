package com.confia.cashbox.infrastructure;

import static confia.generated.jooq.tables.CashboxSessionApproval.CASHBOX_SESSION_APPROVAL;

import com.confia.cashbox.application.CashApprovalRepository;
import com.confia.cashbox.domain.CashApproval;
import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The jOOQ adapter of {@link CashApprovalRepository} over {@code cashbox_session_approval}. It inserts and reads: no statement
 * updates or deletes an approval, and the database refuses an approval by the cashier of the session.
 */
public final class JooqCashApprovalRepository implements CashApprovalRepository {

    private final DSLContext dsl;

    public JooqCashApprovalRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, CashApproval approval) {
        CashboxWrites.inserting(() -> dsl.insertInto(CASHBOX_SESSION_APPROVAL)
                .set(CASHBOX_SESSION_APPROVAL.INSTITUTION_ID, institution.value())
                .set(CASHBOX_SESSION_APPROVAL.ID, approval.id())
                .set(CASHBOX_SESSION_APPROVAL.SESSION_ID, approval.sessionId())
                .set(CASHBOX_SESSION_APPROVAL.APPROVED_BY, approval.approvedBy())
                .set(CASHBOX_SESSION_APPROVAL.NOTE, approval.note())
                .set(CASHBOX_SESSION_APPROVAL.APPROVED_AT, CashboxWrites.toDatabase(approval.approvedAt()))
                .set(CASHBOX_SESSION_APPROVAL.REQUEST_KEY, approval.requestKey())
                .set(CASHBOX_SESSION_APPROVAL.REQUEST_HASH, approval.requestHash())
                .execute());
    }

    @Override
    public Optional<CashApproval> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(CASHBOX_SESSION_APPROVAL)
                .where(CASHBOX_SESSION_APPROVAL.INSTITUTION_ID.eq(institution.value()))
                .and(CASHBOX_SESSION_APPROVAL.REQUEST_KEY.eq(requestKey))
                .fetchOptional(JooqCashApprovalRepository::toApproval);
    }

    @Override
    public Optional<CashApproval> findBySession(InstitutionId institution, UUID sessionId) {
        return dsl.selectFrom(CASHBOX_SESSION_APPROVAL)
                .where(CASHBOX_SESSION_APPROVAL.INSTITUTION_ID.eq(institution.value()))
                .and(CASHBOX_SESSION_APPROVAL.SESSION_ID.eq(sessionId))
                .fetchOptional(JooqCashApprovalRepository::toApproval);
    }

    @Override
    public Map<UUID, CashApproval> findBySessions(InstitutionId institution, Collection<UUID> sessionIds) {
        Map<UUID, CashApproval> bySession = new LinkedHashMap<>();
        if (sessionIds.isEmpty()) {
            return bySession;
        }
        dsl.selectFrom(CASHBOX_SESSION_APPROVAL)
                .where(CASHBOX_SESSION_APPROVAL.INSTITUTION_ID.eq(institution.value()))
                .and(CASHBOX_SESSION_APPROVAL.SESSION_ID.in(sessionIds)).fetch(JooqCashApprovalRepository::toApproval)
                .forEach(approval -> bySession.put(approval.sessionId(), approval));
        return bySession;
    }

    private static CashApproval toApproval(Record row) {
        return new CashApproval(row.get(CASHBOX_SESSION_APPROVAL.ID), row.get(CASHBOX_SESSION_APPROVAL.SESSION_ID),
                row.get(CASHBOX_SESSION_APPROVAL.APPROVED_BY), row.get(CASHBOX_SESSION_APPROVAL.NOTE),
                CashboxWrites.fromDatabase(row.get(CASHBOX_SESSION_APPROVAL.APPROVED_AT)),
                row.get(CASHBOX_SESSION_APPROVAL.REQUEST_KEY), row.get(CASHBOX_SESSION_APPROVAL.REQUEST_HASH).strip());
    }
}
