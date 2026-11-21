package com.confia.payments.infrastructure;

import static confia.generated.jooq.tables.PaymentsPostingAccount.PAYMENTS_POSTING_ACCOUNT;

import com.confia.kernel.InstitutionId;
import com.confia.payments.application.PostingAccountRepository;
import com.confia.payments.domain.PostingPurpose;
import com.confia.payments.domain.ResourceNotFoundException;
import com.confia.payments.domain.StaleVersionException;
import confia.generated.jooq.tables.records.PaymentsPostingAccountRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * The jOOQ adapter of {@link PostingAccountRepository}: the map of posting accounts of payments, read from and written to the
 * table as data. It inserts a row the first time a purpose is set and replaces its account afterwards; no statement deletes a row,
 * and the purpose of a row is never written after the insert (the database refuses it too).
 */
public final class JooqPostingAccountRepository implements PostingAccountRepository {

    private static final String UNIQUE_VIOLATION = "23505";

    private final DSLContext dsl;

    public JooqPostingAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<PostingAccountRow> listRows(InstitutionId institution) {
        return dsl.selectFrom(PAYMENTS_POSTING_ACCOUNT)
                .where(PAYMENTS_POSTING_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .orderBy(PAYMENTS_POSTING_ACCOUNT.PURPOSE)
                .fetch(JooqPostingAccountRepository::toRow);
    }

    @Override
    public Optional<PostingAccountRow> findRow(InstitutionId institution, PostingPurpose purpose) {
        return dsl.selectFrom(PAYMENTS_POSTING_ACCOUNT)
                .where(PAYMENTS_POSTING_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(PAYMENTS_POSTING_ACCOUNT.PURPOSE.eq(purpose.name()))
                .forUpdate().fetchOptional().map(JooqPostingAccountRepository::toRow);
    }

    @Override
    public PostingAccountRow insert(InstitutionId institution, UUID id, PostingPurpose purpose, UUID ledgerAccountId,
            Instant now) {
        try {
            dsl.insertInto(PAYMENTS_POSTING_ACCOUNT)
                    .set(PAYMENTS_POSTING_ACCOUNT.INSTITUTION_ID, institution.value())
                    .set(PAYMENTS_POSTING_ACCOUNT.ID, id)
                    .set(PAYMENTS_POSTING_ACCOUNT.PURPOSE, purpose.name())
                    .set(PAYMENTS_POSTING_ACCOUNT.LEDGER_ACCOUNT_ID, ledgerAccountId)
                    .set(PAYMENTS_POSTING_ACCOUNT.VERSION, 0L)
                    .set(PAYMENTS_POSTING_ACCOUNT.CREATED_AT, PaymentsWrites.toDatabase(now))
                    .set(PAYMENTS_POSTING_ACCOUNT.UPDATED_AT, PaymentsWrites.toDatabase(now))
                    .execute();
        } catch (DataAccessException e) {
            if (isUniqueViolation(e)) {
                // Somebody set the same purpose between the read and the insert.
                throw new StaleVersionException();
            }
            throw e;
        }
        return new PostingAccountRow(id, purpose, ledgerAccountId, 0L, now);
    }

    @Override
    public PostingAccountRow replaceAccount(InstitutionId institution, PostingAccountRow row, UUID ledgerAccountId,
            long expectedVersion, Instant now) {
        long nextVersion = expectedVersion + 1;
        int rows = dsl.update(PAYMENTS_POSTING_ACCOUNT)
                .set(PAYMENTS_POSTING_ACCOUNT.LEDGER_ACCOUNT_ID, ledgerAccountId)
                .set(PAYMENTS_POSTING_ACCOUNT.VERSION, nextVersion)
                .set(PAYMENTS_POSTING_ACCOUNT.UPDATED_AT, PaymentsWrites.toDatabase(now))
                .where(PAYMENTS_POSTING_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(PAYMENTS_POSTING_ACCOUNT.ID.eq(row.id()))
                .and(PAYMENTS_POSTING_ACCOUNT.VERSION.eq(expectedVersion))
                .execute();
        if (rows == 0) {
            boolean exists = dsl.fetchExists(PAYMENTS_POSTING_ACCOUNT,
                    PAYMENTS_POSTING_ACCOUNT.INSTITUTION_ID.eq(institution.value())
                            .and(PAYMENTS_POSTING_ACCOUNT.ID.eq(row.id())));
            throw exists ? new StaleVersionException() : new ResourceNotFoundException();
        }
        return new PostingAccountRow(row.id(), row.purpose(), ledgerAccountId, nextVersion, now);
    }

    private static PostingAccountRow toRow(PaymentsPostingAccountRecord row) {
        return new PostingAccountRow(row.getId(), PostingPurpose.parse(row.getPurpose()), row.getLedgerAccountId(),
                row.getVersion(), row.getUpdatedAt().toInstant());
    }

    private static boolean isUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres && UNIQUE_VIOLATION.equals(postgres.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
