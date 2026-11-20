package com.confia.cashbox.infrastructure;

import static confia.generated.jooq.tables.CashboxMovement.CASHBOX_MOVEMENT;

import com.confia.cashbox.application.CashMovementRepository;
import com.confia.cashbox.domain.CashMovement;
import com.confia.cashbox.domain.MovementType;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The jOOQ adapter of {@link CashMovementRepository} over {@code cashbox_movement}. It inserts and reads: no statement here
 * updates or deletes a movement, and the database refuses them to every role, as it refuses a movement on a session that is not
 * open (INV-21).
 */
public final class JooqCashMovementRepository implements CashMovementRepository {

    private final DSLContext dsl;

    public JooqCashMovementRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, CashMovement movement) {
        CashboxWrites.inserting(() -> dsl.insertInto(CASHBOX_MOVEMENT)
                .set(CASHBOX_MOVEMENT.INSTITUTION_ID, institution.value())
                .set(CASHBOX_MOVEMENT.ID, movement.id())
                .set(CASHBOX_MOVEMENT.SESSION_ID, movement.sessionId())
                .set(CASHBOX_MOVEMENT.TYPE, movement.type().code())
                .set(CASHBOX_MOVEMENT.AMOUNT, MoneyColumns.amountColumn(movement.amount()))
                .set(CASHBOX_MOVEMENT.CURRENCY, MoneyColumns.currencyColumn(movement.amount()))
                .set(CASHBOX_MOVEMENT.NOTES, movement.notes())
                .set(CASHBOX_MOVEMENT.REQUEST_KEY, movement.requestKey())
                .set(CASHBOX_MOVEMENT.REQUEST_HASH, movement.requestHash())
                .set(CASHBOX_MOVEMENT.CREATED_BY, movement.createdBy())
                .set(CASHBOX_MOVEMENT.CREATED_AT, CashboxWrites.toDatabase(movement.createdAt()))
                .execute());
    }

    @Override
    public Optional<CashMovement> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(CASHBOX_MOVEMENT).where(CASHBOX_MOVEMENT.INSTITUTION_ID.eq(institution.value()))
                .and(CASHBOX_MOVEMENT.REQUEST_KEY.eq(requestKey)).fetchOptional(JooqCashMovementRepository::toMovement);
    }

    @Override
    public Money expectedAfter(InstitutionId institution, UUID movementId) {
        Record row = dsl.select(CASHBOX_MOVEMENT.EXPECTED_AFTER, CASHBOX_MOVEMENT.CURRENCY).from(CASHBOX_MOVEMENT)
                .where(CASHBOX_MOVEMENT.INSTITUTION_ID.eq(institution.value()))
                .and(CASHBOX_MOVEMENT.ID.eq(movementId)).fetchOne();
        if (row == null) {
            throw new IllegalStateException("the movement was just read and is gone");
        }
        return MoneyColumns.toMoney(row.get(CASHBOX_MOVEMENT.EXPECTED_AFTER), row.get(CASHBOX_MOVEMENT.CURRENCY));
    }

    @Override
    public List<CashMovement> findBySession(InstitutionId institution, UUID sessionId) {
        return dsl.selectFrom(CASHBOX_MOVEMENT).where(CASHBOX_MOVEMENT.INSTITUTION_ID.eq(institution.value()))
                .and(CASHBOX_MOVEMENT.SESSION_ID.eq(sessionId))
                .orderBy(CASHBOX_MOVEMENT.CREATED_AT.asc(), CASHBOX_MOVEMENT.ID.asc())
                .fetch(JooqCashMovementRepository::toMovement);
    }

    private static CashMovement toMovement(Record row) {
        return new CashMovement(row.get(CASHBOX_MOVEMENT.ID), row.get(CASHBOX_MOVEMENT.SESSION_ID),
                MovementType.ofCode(row.get(CASHBOX_MOVEMENT.TYPE)).orElseThrow(),
                MoneyColumns.toMoney(row.get(CASHBOX_MOVEMENT.AMOUNT), row.get(CASHBOX_MOVEMENT.CURRENCY)),
                row.get(CASHBOX_MOVEMENT.NOTES), row.get(CASHBOX_MOVEMENT.REQUEST_KEY),
                row.get(CASHBOX_MOVEMENT.REQUEST_HASH).strip(), row.get(CASHBOX_MOVEMENT.CREATED_BY),
                CashboxWrites.fromDatabase(row.get(CASHBOX_MOVEMENT.CREATED_AT)));
    }
}
