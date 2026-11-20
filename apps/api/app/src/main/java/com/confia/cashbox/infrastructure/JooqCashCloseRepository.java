package com.confia.cashbox.infrastructure;

import static confia.generated.jooq.tables.CashboxSessionClose.CASHBOX_SESSION_CLOSE;

import com.confia.cashbox.application.CashCloseRepository;
import com.confia.cashbox.domain.CashClose;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The jOOQ adapter of {@link CashCloseRepository} over {@code cashbox_session_close}. It inserts and reads: the difference is a
 * generated column of the database and is never sent (INV-23), and no statement updates or deletes a close.
 */
public final class JooqCashCloseRepository implements CashCloseRepository {

    private final DSLContext dsl;

    public JooqCashCloseRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, CashClose close) {
        CashboxWrites.inserting(() -> dsl.insertInto(CASHBOX_SESSION_CLOSE)
                .set(CASHBOX_SESSION_CLOSE.INSTITUTION_ID, institution.value())
                .set(CASHBOX_SESSION_CLOSE.ID, close.id())
                .set(CASHBOX_SESSION_CLOSE.SESSION_ID, close.sessionId())
                .set(CASHBOX_SESSION_CLOSE.CURRENCY, MoneyColumns.currencyColumn(close.declaredAmount()))
                .set(CASHBOX_SESSION_CLOSE.DECLARED_AMOUNT, MoneyColumns.amountColumn(close.declaredAmount()))
                .set(CASHBOX_SESSION_CLOSE.EXPECTED_AMOUNT, MoneyColumns.amountColumn(close.expectedAmount()))
                .set(CASHBOX_SESSION_CLOSE.DIFFERENCE_REASON, close.differenceReason())
                .set(CASHBOX_SESSION_CLOSE.CLOSED_BY, close.closedBy())
                .set(CASHBOX_SESSION_CLOSE.CLOSED_AT, CashboxWrites.toDatabase(close.closedAt()))
                .set(CASHBOX_SESSION_CLOSE.REQUEST_KEY, close.requestKey())
                .set(CASHBOX_SESSION_CLOSE.REQUEST_HASH, close.requestHash())
                .execute());
    }

    @Override
    public Optional<CashClose> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(CASHBOX_SESSION_CLOSE)
                .where(CASHBOX_SESSION_CLOSE.INSTITUTION_ID.eq(institution.value()))
                .and(CASHBOX_SESSION_CLOSE.REQUEST_KEY.eq(requestKey)).fetchOptional(JooqCashCloseRepository::toClose);
    }

    @Override
    public Optional<CashClose> findBySession(InstitutionId institution, UUID sessionId) {
        return dsl.selectFrom(CASHBOX_SESSION_CLOSE)
                .where(CASHBOX_SESSION_CLOSE.INSTITUTION_ID.eq(institution.value()))
                .and(CASHBOX_SESSION_CLOSE.SESSION_ID.eq(sessionId)).fetchOptional(JooqCashCloseRepository::toClose);
    }

    @Override
    public Map<UUID, CashClose> findBySessions(InstitutionId institution, Collection<UUID> sessionIds) {
        Map<UUID, CashClose> bySession = new LinkedHashMap<>();
        if (sessionIds.isEmpty()) {
            return bySession;
        }
        dsl.selectFrom(CASHBOX_SESSION_CLOSE).where(CASHBOX_SESSION_CLOSE.INSTITUTION_ID.eq(institution.value()))
                .and(CASHBOX_SESSION_CLOSE.SESSION_ID.in(sessionIds)).fetch(JooqCashCloseRepository::toClose)
                .forEach(close -> bySession.put(close.sessionId(), close));
        return bySession;
    }

    private static CashClose toClose(Record row) {
        String currency = row.get(CASHBOX_SESSION_CLOSE.CURRENCY);
        return new CashClose(row.get(CASHBOX_SESSION_CLOSE.ID), row.get(CASHBOX_SESSION_CLOSE.SESSION_ID),
                MoneyColumns.toMoney(row.get(CASHBOX_SESSION_CLOSE.DECLARED_AMOUNT), currency),
                MoneyColumns.toMoney(row.get(CASHBOX_SESSION_CLOSE.EXPECTED_AMOUNT), currency),
                row.get(CASHBOX_SESSION_CLOSE.DIFFERENCE_REASON), row.get(CASHBOX_SESSION_CLOSE.CLOSED_BY),
                CashboxWrites.fromDatabase(row.get(CASHBOX_SESSION_CLOSE.CLOSED_AT)),
                row.get(CASHBOX_SESSION_CLOSE.REQUEST_KEY), row.get(CASHBOX_SESSION_CLOSE.REQUEST_HASH).strip());
    }
}
