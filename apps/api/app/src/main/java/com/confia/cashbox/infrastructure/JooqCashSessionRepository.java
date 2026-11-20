package com.confia.cashbox.infrastructure;

import static confia.generated.jooq.tables.CashboxSession.CASHBOX_SESSION;

import com.confia.cashbox.application.CashSessionRepository;
import com.confia.cashbox.application.Page;
import com.confia.cashbox.application.PageRequest;
import com.confia.cashbox.application.SessionFilter;
import com.confia.cashbox.domain.CashSession;
import com.confia.cashbox.domain.CashSessionStatus;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.OrderField;
import org.jooq.Record;

/**
 * The jOOQ adapter of {@link CashSessionRepository} over {@code cashbox_session}. It inserts, reads and advances the status:
 * the only column it ever updates, and the database refuses any other change, any delete and a status move without its record.
 * The amount is built and taken apart only by {@link MoneyColumns}.
 */
public final class JooqCashSessionRepository implements CashSessionRepository {

    private static final ZoneId INSTITUTION_ZONE = ZoneId.of("America/Tegucigalpa");

    private final DSLContext dsl;

    public JooqCashSessionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, CashSession session) {
        CashboxWrites.inserting(() -> dsl.insertInto(CASHBOX_SESSION)
                .set(CASHBOX_SESSION.INSTITUTION_ID, institution.value())
                .set(CASHBOX_SESSION.ID, session.id())
                .set(CASHBOX_SESSION.CASHIER_ID, session.cashierId())
                .set(CASHBOX_SESSION.CURRENCY, MoneyColumns.currencyColumn(session.openingFloat()))
                .set(CASHBOX_SESSION.OPENING_FLOAT, MoneyColumns.amountColumn(session.openingFloat()))
                .set(CASHBOX_SESSION.STATUS, session.status().code())
                .set(CASHBOX_SESSION.OPENED_AT, CashboxWrites.toDatabase(session.openedAt()))
                .set(CASHBOX_SESSION.REQUEST_KEY, session.requestKey())
                .set(CASHBOX_SESSION.REQUEST_HASH, session.requestHash())
                .execute());
    }

    @Override
    public Optional<CashSession> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(CASHBOX_SESSION).where(inInstitution(institution))
                .and(CASHBOX_SESSION.REQUEST_KEY.eq(requestKey)).fetchOptional(JooqCashSessionRepository::toSession);
    }

    @Override
    public Optional<CashSession> findById(InstitutionId institution, UUID id) {
        return dsl.selectFrom(CASHBOX_SESSION).where(inInstitution(institution)).and(CASHBOX_SESSION.ID.eq(id))
                .fetchOptional(JooqCashSessionRepository::toSession);
    }

    @Override
    public Optional<CashSession> findForUpdate(InstitutionId institution, UUID id) {
        return dsl.selectFrom(CASHBOX_SESSION).where(inInstitution(institution)).and(CASHBOX_SESSION.ID.eq(id))
                .forUpdate().fetchOptional(JooqCashSessionRepository::toSession);
    }

    @Override
    public Optional<CashSession> findOpenByCashier(InstitutionId institution, UUID cashier) {
        return dsl.selectFrom(CASHBOX_SESSION).where(inInstitution(institution))
                .and(CASHBOX_SESSION.CASHIER_ID.eq(cashier)).and(CASHBOX_SESSION.STATUS.eq(CashSessionStatus.OPEN.code()))
                .fetchOptional(JooqCashSessionRepository::toSession);
    }

    @Override
    public void advanceStatus(InstitutionId institution, UUID id, CashSessionStatus to) {
        dsl.update(CASHBOX_SESSION).set(CASHBOX_SESSION.STATUS, to.code())
                .where(inInstitution(institution)).and(CASHBOX_SESSION.ID.eq(id)).execute();
    }

    @Override
    public Page<CashSession> list(InstitutionId institution, SessionFilter filter, PageRequest page) {
        Condition where = inInstitution(institution);
        if (filter.status() != null) {
            where = where.and(CASHBOX_SESSION.STATUS.eq(filter.status()));
        }
        if (filter.cashier() != null) {
            where = where.and(CASHBOX_SESSION.CASHIER_ID.eq(filter.cashier()));
        }
        if (filter.from() != null) {
            where = where.and(CASHBOX_SESSION.OPENED_AT.ge(
                    CashboxWrites.toDatabase(filter.from().atStartOfDay(INSTITUTION_ZONE).toInstant())));
        }
        if (filter.to() != null) {
            where = where.and(CASHBOX_SESSION.OPENED_AT.lt(
                    CashboxWrites.toDatabase(filter.to().plusDays(1).atStartOfDay(INSTITUTION_ZONE).toInstant())));
        }
        Long total = dsl.selectCount().from(CASHBOX_SESSION).where(where).fetchOne(0, Long.class);
        OrderField<?> primary = "status".equals(page.sortField())
                ? (page.ascending() ? CASHBOX_SESSION.STATUS.asc() : CASHBOX_SESSION.STATUS.desc())
                : (page.ascending() ? CASHBOX_SESSION.OPENED_AT.asc() : CASHBOX_SESSION.OPENED_AT.desc());
        List<CashSession> items = dsl.selectFrom(CASHBOX_SESSION).where(where)
                .orderBy(primary, CASHBOX_SESSION.ID.desc())
                .limit(page.size()).offset(page.offset()).fetch(JooqCashSessionRepository::toSession);
        return new Page<>(items, total == null ? 0 : total, page.page(), page.size());
    }

    private static Condition inInstitution(InstitutionId institution) {
        return CASHBOX_SESSION.INSTITUTION_ID.eq(institution.value());
    }

    private static CashSession toSession(Record row) {
        return new CashSession(row.get(CASHBOX_SESSION.ID), row.get(CASHBOX_SESSION.CASHIER_ID),
                MoneyColumns.toMoney(row.get(CASHBOX_SESSION.OPENING_FLOAT), row.get(CASHBOX_SESSION.CURRENCY)),
                CashSessionStatus.ofCode(row.get(CASHBOX_SESSION.STATUS)).orElseThrow(),
                CashboxWrites.fromDatabase(row.get(CASHBOX_SESSION.OPENED_AT)), row.get(CASHBOX_SESSION.REQUEST_KEY),
                row.get(CASHBOX_SESSION.REQUEST_HASH).strip());
    }
}
