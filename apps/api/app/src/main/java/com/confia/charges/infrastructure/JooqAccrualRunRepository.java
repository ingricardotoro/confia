package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesAccrualRun.CHARGES_ACCRUAL_RUN;

import com.confia.charges.application.AccrualRunRepository;
import com.confia.charges.application.Page;
import com.confia.charges.domain.AccrualCounters;
import com.confia.charges.domain.AccrualRun;
import com.confia.charges.domain.AccrualRunInProgressException;
import com.confia.charges.domain.RunStatus;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.ChargesAccrualRunRecord;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * The jOOQ adapter of {@link AccrualRunRepository} over {@code charges_accrual_run}. A run is inserted queued and updated only
 * in its status, dates, counters and failure code; nothing is deleted. The two unique indexes of the table become domain
 * errors by the name of the constraint the server reports, never by message text.
 */
public final class JooqAccrualRunRepository implements AccrualRunRepository {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String ACTIVE_INDEX = "charges_accrual_run_active_uq";
    private static final String KEY_INDEX = "charges_accrual_run_key_uq";

    private final DSLContext dsl;

    public JooqAccrualRunRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<AccrualRun> findById(InstitutionId institution, UUID id) {
        return dsl.selectFrom(CHARGES_ACCRUAL_RUN).where(inInstitution(institution)).and(CHARGES_ACCRUAL_RUN.ID.eq(id))
                .fetchOptional().map(JooqAccrualRunRepository::toDomain);
    }

    @Override
    public Optional<AccrualRun> lockById(InstitutionId institution, UUID id) {
        return dsl.selectFrom(CHARGES_ACCRUAL_RUN).where(inInstitution(institution)).and(CHARGES_ACCRUAL_RUN.ID.eq(id))
                .forUpdate().fetchOptional().map(JooqAccrualRunRepository::toDomain);
    }

    @Override
    public Optional<AccrualRun> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(CHARGES_ACCRUAL_RUN).where(inInstitution(institution))
                .and(CHARGES_ACCRUAL_RUN.REQUEST_KEY.eq(requestKey)).fetchOptional()
                .map(JooqAccrualRunRepository::toDomain);
    }

    @Override
    public Optional<AccrualRun> findActive(InstitutionId institution, UUID planId, LocalDate periodStart) {
        return dsl.selectFrom(CHARGES_ACCRUAL_RUN).where(inInstitution(institution))
                .and(CHARGES_ACCRUAL_RUN.PLAN_ID.eq(planId)).and(CHARGES_ACCRUAL_RUN.PERIOD_START.eq(periodStart))
                .and(CHARGES_ACCRUAL_RUN.STATUS.in(RunStatus.QUEUED.name(), RunStatus.RUNNING.name()))
                .fetchOptional().map(JooqAccrualRunRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institution, AccrualRun run) {
        try {
            dsl.insertInto(CHARGES_ACCRUAL_RUN)
                    .set(CHARGES_ACCRUAL_RUN.INSTITUTION_ID, institution.value())
                    .set(CHARGES_ACCRUAL_RUN.ID, run.id())
                    .set(CHARGES_ACCRUAL_RUN.PLAN_ID, run.planId())
                    .set(CHARGES_ACCRUAL_RUN.PERIOD_START, run.periodStart())
                    .set(CHARGES_ACCRUAL_RUN.STATUS, run.status().name())
                    .set(CHARGES_ACCRUAL_RUN.REQUEST_KEY, run.requestKey())
                    .set(CHARGES_ACCRUAL_RUN.REQUEST_HASH, run.requestHash())
                    .set(CHARGES_ACCRUAL_RUN.PREVIEW_DIGEST, run.previewDigest())
                    .set(CHARGES_ACCRUAL_RUN.REQUESTED_BY, run.requestedBy())
                    .set(CHARGES_ACCRUAL_RUN.QUEUED_AT, ChargesWrites.toDatabase(run.queuedAt()))
                    .execute();
        } catch (DataAccessException e) {
            String unique = violatedConstraint(e);
            if (ACTIVE_INDEX.equals(unique)) {
                throw new AccrualRunInProgressException();
            }
            if (KEY_INDEX.equals(unique)) {
                throw new RequestKeyTakenException();
            }
            throw e;
        }
    }

    @Override
    public void update(InstitutionId institution, AccrualRun run) {
        dsl.update(CHARGES_ACCRUAL_RUN)
                .set(CHARGES_ACCRUAL_RUN.STATUS, run.status().name())
                .set(CHARGES_ACCRUAL_RUN.STARTED_AT, run.startedAt() == null ? null : ChargesWrites.toDatabase(run.startedAt()))
                .set(CHARGES_ACCRUAL_RUN.FINISHED_AT,
                        run.finishedAt() == null ? null : ChargesWrites.toDatabase(run.finishedAt()))
                .set(CHARGES_ACCRUAL_RUN.STUDENTS_EVALUATED, run.counters().studentsEvaluated())
                .set(CHARGES_ACCRUAL_RUN.CHARGES_CREATED, run.counters().chargesCreated())
                .set(CHARGES_ACCRUAL_RUN.CHARGES_EXISTING, run.counters().chargesExisting())
                .set(CHARGES_ACCRUAL_RUN.CHARGES_FAILED, run.counters().chargesFailed())
                .set(CHARGES_ACCRUAL_RUN.FAILURE_CODE, run.failureCode())
                .set(CHARGES_ACCRUAL_RUN.UPDATED_AT, OffsetDateTime.now(java.time.ZoneOffset.UTC))
                .where(inInstitution(institution)).and(CHARGES_ACCRUAL_RUN.ID.eq(run.id()))
                .execute();
    }

    @Override
    public Page<AccrualRun> list(InstitutionId institution, UUID planId, int page, int size) {
        Condition where = inInstitution(institution);
        if (planId != null) {
            where = where.and(CHARGES_ACCRUAL_RUN.PLAN_ID.eq(planId));
        }
        Long total = dsl.selectCount().from(CHARGES_ACCRUAL_RUN).where(where).fetchOne(0, Long.class);
        List<AccrualRun> items = dsl.selectFrom(CHARGES_ACCRUAL_RUN).where(where)
                .orderBy(CHARGES_ACCRUAL_RUN.QUEUED_AT.desc(), CHARGES_ACCRUAL_RUN.ID.desc())
                .limit(size).offset((long) page * size).fetch(JooqAccrualRunRepository::toDomain);
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    @Override
    public List<AccrualRun> latestByPeriod(InstitutionId institution, UUID planId) {
        return dsl.selectFrom(CHARGES_ACCRUAL_RUN).where(inInstitution(institution))
                .and(CHARGES_ACCRUAL_RUN.PLAN_ID.eq(planId))
                .orderBy(CHARGES_ACCRUAL_RUN.QUEUED_AT.desc(), CHARGES_ACCRUAL_RUN.ID.desc())
                .fetch(JooqAccrualRunRepository::toDomain);
    }

    private static Condition inInstitution(InstitutionId institution) {
        return CHARGES_ACCRUAL_RUN.INSTITUTION_ID.eq(institution.value());
    }

    private static AccrualRun toDomain(ChargesAccrualRunRecord row) {
        return new AccrualRun(row.getId(), row.getPlanId(), row.getPeriodStart(), RunStatus.valueOf(row.getStatus()),
                row.getRequestKey(), row.getRequestHash().strip(),
                row.getPreviewDigest() == null ? null : row.getPreviewDigest().strip(), row.getRequestedBy(),
                ChargesWrites.fromDatabase(row.getQueuedAt()),
                row.getStartedAt() == null ? null : ChargesWrites.fromDatabase(row.getStartedAt()),
                row.getFinishedAt() == null ? null : ChargesWrites.fromDatabase(row.getFinishedAt()),
                new AccrualCounters(row.getStudentsEvaluated(), row.getChargesCreated(), row.getChargesExisting(),
                        row.getChargesFailed()),
                row.getFailureCode());
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
}
