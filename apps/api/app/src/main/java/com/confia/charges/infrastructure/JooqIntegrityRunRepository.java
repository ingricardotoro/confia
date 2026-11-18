package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesIntegrityDiscrepancy.CHARGES_INTEGRITY_DISCREPANCY;
import static confia.generated.jooq.tables.ChargesIntegrityRun.CHARGES_INTEGRITY_RUN;

import com.confia.charges.application.IntegrityRunRepository;
import com.confia.charges.application.Page;
import com.confia.charges.domain.IntegrityFinding;
import com.confia.charges.domain.IntegrityKind;
import com.confia.charges.domain.IntegrityRun;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The jOOQ adapter of {@link IntegrityRunRepository} over {@code charges_integrity_run} and {@code
 * charges_integrity_discrepancy}. It inserts and reads; the database refuses an update, a delete or a truncate to every role.
 * Amounts go through {@link MoneyColumns} and nowhere else.
 */
public final class JooqIntegrityRunRepository implements IntegrityRunRepository {

    private final DSLContext dsl;

    public JooqIntegrityRunRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, IntegrityRun run, List<IntegrityFinding> findings) {
        dsl.insertInto(CHARGES_INTEGRITY_RUN)
                .set(CHARGES_INTEGRITY_RUN.INSTITUTION_ID, institution.value())
                .set(CHARGES_INTEGRITY_RUN.ID, run.id())
                .set(CHARGES_INTEGRITY_RUN.STARTED_AT, ChargesWrites.toDatabase(run.startedAt()))
                .set(CHARGES_INTEGRITY_RUN.FINISHED_AT, ChargesWrites.toDatabase(run.finishedAt()))
                .set(CHARGES_INTEGRITY_RUN.STATUS, run.status())
                .set(CHARGES_INTEGRITY_RUN.CHARGES_CHECKED, run.chargesChecked())
                .set(CHARGES_INTEGRITY_RUN.POSTINGS_CHECKED, run.postingsChecked())
                .set(CHARGES_INTEGRITY_RUN.DISCREPANCY_COUNT, run.discrepancyCount())
                .execute();
        for (IntegrityFinding finding : findings) {
            Money expected = finding.expected();
            Money actual = finding.actual();
            dsl.insertInto(CHARGES_INTEGRITY_DISCREPANCY)
                    .set(CHARGES_INTEGRITY_DISCREPANCY.INSTITUTION_ID, institution.value())
                    .set(CHARGES_INTEGRITY_DISCREPANCY.ID, UUID.randomUUID())
                    .set(CHARGES_INTEGRITY_DISCREPANCY.RUN_ID, run.id())
                    .set(CHARGES_INTEGRITY_DISCREPANCY.KIND, finding.kind().name())
                    .set(CHARGES_INTEGRITY_DISCREPANCY.CHARGE_ID, finding.chargeId())
                    .set(CHARGES_INTEGRITY_DISCREPANCY.LEDGER_TRANSACTION_ID, finding.ledgerTransactionId())
                    .set(CHARGES_INTEGRITY_DISCREPANCY.CURRENCY,
                            expected == null ? null : MoneyColumns.currencyColumn(expected))
                    .set(CHARGES_INTEGRITY_DISCREPANCY.EXPECTED_AMOUNT,
                            expected == null ? null : MoneyColumns.amountColumn(expected))
                    .set(CHARGES_INTEGRITY_DISCREPANCY.ACTUAL_AMOUNT,
                            actual == null ? null : MoneyColumns.amountColumn(actual))
                    .execute();
        }
    }

    @Override
    public Page<IntegrityRun> list(InstitutionId institution, int page, int size) {
        long total = dsl.selectCount().from(CHARGES_INTEGRITY_RUN)
                .where(CHARGES_INTEGRITY_RUN.INSTITUTION_ID.eq(institution.value())).fetchOne(0, long.class);
        List<IntegrityRun> rows = dsl.selectFrom(CHARGES_INTEGRITY_RUN)
                .where(CHARGES_INTEGRITY_RUN.INSTITUTION_ID.eq(institution.value()))
                .orderBy(CHARGES_INTEGRITY_RUN.FINISHED_AT.desc(), CHARGES_INTEGRITY_RUN.ID.asc())
                .limit(size).offset((long) page * size)
                .fetch(JooqIntegrityRunRepository::toRun);
        return new Page<>(rows, total, page, size);
    }

    @Override
    public Optional<IntegrityRun> latest(InstitutionId institution) {
        return dsl.selectFrom(CHARGES_INTEGRITY_RUN)
                .where(CHARGES_INTEGRITY_RUN.INSTITUTION_ID.eq(institution.value()))
                .orderBy(CHARGES_INTEGRITY_RUN.FINISHED_AT.desc(), CHARGES_INTEGRITY_RUN.ID.asc())
                .limit(1).fetchOptional(JooqIntegrityRunRepository::toRun);
    }

    @Override
    public Optional<IntegrityRun> findById(InstitutionId institution, UUID runId) {
        return dsl.selectFrom(CHARGES_INTEGRITY_RUN)
                .where(CHARGES_INTEGRITY_RUN.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_INTEGRITY_RUN.ID.eq(runId))
                .fetchOptional(JooqIntegrityRunRepository::toRun);
    }

    @Override
    public List<IntegrityFinding> findings(InstitutionId institution, UUID runId) {
        return dsl.selectFrom(CHARGES_INTEGRITY_DISCREPANCY)
                .where(CHARGES_INTEGRITY_DISCREPANCY.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_INTEGRITY_DISCREPANCY.RUN_ID.eq(runId))
                .orderBy(CHARGES_INTEGRITY_DISCREPANCY.KIND, CHARGES_INTEGRITY_DISCREPANCY.CHARGE_ID,
                        CHARGES_INTEGRITY_DISCREPANCY.LEDGER_TRANSACTION_ID, CHARGES_INTEGRITY_DISCREPANCY.ID)
                .fetch(JooqIntegrityRunRepository::toFinding);
    }

    private static IntegrityRun toRun(Record row) {
        return new IntegrityRun(row.get(CHARGES_INTEGRITY_RUN.ID),
                ChargesWrites.fromDatabase(row.get(CHARGES_INTEGRITY_RUN.STARTED_AT)),
                ChargesWrites.fromDatabase(row.get(CHARGES_INTEGRITY_RUN.FINISHED_AT)),
                row.get(CHARGES_INTEGRITY_RUN.CHARGES_CHECKED), row.get(CHARGES_INTEGRITY_RUN.POSTINGS_CHECKED),
                row.get(CHARGES_INTEGRITY_RUN.DISCREPANCY_COUNT));
    }

    private static IntegrityFinding toFinding(Record row) {
        String currency = row.get(CHARGES_INTEGRITY_DISCREPANCY.CURRENCY);
        Money expected = row.get(CHARGES_INTEGRITY_DISCREPANCY.EXPECTED_AMOUNT) == null ? null
                : MoneyColumns.toMoney(row.get(CHARGES_INTEGRITY_DISCREPANCY.EXPECTED_AMOUNT), currency);
        Money actual = row.get(CHARGES_INTEGRITY_DISCREPANCY.ACTUAL_AMOUNT) == null ? null
                : MoneyColumns.toMoney(row.get(CHARGES_INTEGRITY_DISCREPANCY.ACTUAL_AMOUNT), currency);
        return new IntegrityFinding(IntegrityKind.valueOf(row.get(CHARGES_INTEGRITY_DISCREPANCY.KIND)),
                row.get(CHARGES_INTEGRITY_DISCREPANCY.CHARGE_ID),
                row.get(CHARGES_INTEGRITY_DISCREPANCY.LEDGER_TRANSACTION_ID), expected, actual);
    }
}
