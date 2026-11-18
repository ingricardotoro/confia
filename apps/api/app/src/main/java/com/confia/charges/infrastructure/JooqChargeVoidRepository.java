package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesChargeVoid.CHARGES_CHARGE_VOID;

import com.confia.charges.application.ChargeVoidRepository;
import com.confia.charges.domain.ChargeVoid;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * The jOOQ adapter of {@link ChargeVoidRepository} over {@code charges_charge_void}. It inserts and reads: no statement here
 * updates or deletes a void, and the database refuses them to every role (INV-47). The database also refuses a void whose
 * ledger reversal was not posted by the same database transaction.
 */
public final class JooqChargeVoidRepository implements ChargeVoidRepository {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String KEY_INDEX = "charges_charge_void_key_uq";

    private final DSLContext dsl;

    public JooqChargeVoidRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, ChargeVoid charge) {
        try {
            insertRow(institution, charge);
        } catch (DataAccessException e) {
            if (KEY_INDEX.equals(violatedConstraint(e))) {
                throw new RequestKeyTakenException();
            }
            throw e;
        }
    }

    private void insertRow(InstitutionId institution, ChargeVoid charge) {
        dsl.insertInto(CHARGES_CHARGE_VOID)
                .set(CHARGES_CHARGE_VOID.INSTITUTION_ID, institution.value())
                .set(CHARGES_CHARGE_VOID.ID, charge.id())
                .set(CHARGES_CHARGE_VOID.CHARGE_ID, charge.chargeId())
                .set(CHARGES_CHARGE_VOID.REVERSAL_TRANSACTION_ID, charge.reversalTransactionId())
                .set(CHARGES_CHARGE_VOID.REASON, charge.reason())
                .set(CHARGES_CHARGE_VOID.REQUEST_KEY, charge.requestKey())
                .set(CHARGES_CHARGE_VOID.REQUEST_HASH, charge.requestHash())
                .set(CHARGES_CHARGE_VOID.VOIDED_BY, charge.voidedBy())
                .set(CHARGES_CHARGE_VOID.VOIDED_AT, ChargesWrites.toDatabase(charge.voidedAt()))
                .execute();
    }

    @Override
    public Optional<ChargeVoid> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(CHARGES_CHARGE_VOID)
                .where(CHARGES_CHARGE_VOID.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_CHARGE_VOID.REQUEST_KEY.eq(requestKey))
                .fetchOptional(JooqChargeVoidRepository::toVoid);
    }

    @Override
    public Optional<ChargeVoid> findByChargeId(InstitutionId institution, UUID chargeId) {
        return dsl.selectFrom(CHARGES_CHARGE_VOID)
                .where(CHARGES_CHARGE_VOID.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_CHARGE_VOID.CHARGE_ID.eq(chargeId))
                .fetchOptional(JooqChargeVoidRepository::toVoid);
    }

    private static String violatedConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres && UNIQUE_VIOLATION.equals(postgres.getSQLState())
                    && postgres.getServerErrorMessage() != null) {
                return postgres.getServerErrorMessage().getConstraint();
            }
        }
        return null;
    }

    private static ChargeVoid toVoid(Record row) {
        return new ChargeVoid(row.get(CHARGES_CHARGE_VOID.ID), row.get(CHARGES_CHARGE_VOID.CHARGE_ID),
                row.get(CHARGES_CHARGE_VOID.REVERSAL_TRANSACTION_ID), row.get(CHARGES_CHARGE_VOID.REASON),
                row.get(CHARGES_CHARGE_VOID.REQUEST_KEY), row.get(CHARGES_CHARGE_VOID.REQUEST_HASH).strip(),
                row.get(CHARGES_CHARGE_VOID.VOIDED_BY), ChargesWrites.fromDatabase(row.get(CHARGES_CHARGE_VOID.VOIDED_AT)));
    }
}
