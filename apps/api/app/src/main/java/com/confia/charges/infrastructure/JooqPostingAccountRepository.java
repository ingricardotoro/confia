package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesPostingAccount.CHARGES_POSTING_ACCOUNT;

import com.confia.charges.application.PostingAccountRepository;
import com.confia.charges.application.PostingAccountRow;
import com.confia.charges.domain.PostingAccountKey;
import com.confia.charges.domain.PostingAccountMap;
import com.confia.charges.domain.PostingPurpose;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.charges.domain.StaleVersionException;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.ChargesPostingAccountRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * The jOOQ adapter of {@link PostingAccountRepository}: the map of posting accounts, read from and written to the table as
 * data. It inserts a row the first time a purpose and category are set and replaces its account afterwards; no statement
 * deletes a row, and the purpose and the category of a row are never written after the insert (the database refuses it too).
 */
public final class JooqPostingAccountRepository implements PostingAccountRepository {

    private static final String UNIQUE_VIOLATION = "23505";

    private final DSLContext dsl;

    public JooqPostingAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<PostingAccountMap.Entry> list(InstitutionId institution) {
        return dsl.selectFrom(CHARGES_POSTING_ACCOUNT)
                .where(CHARGES_POSTING_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .orderBy(CHARGES_POSTING_ACCOUNT.PURPOSE, CHARGES_POSTING_ACCOUNT.CONCEPT_CATEGORY)
                .fetch(row -> new PostingAccountMap.Entry(PostingPurpose.valueOf(row.getPurpose()),
                        row.getConceptCategory(), row.getLedgerAccountId()));
    }

    @Override
    public List<PostingAccountRow> listRows(InstitutionId institution) {
        return dsl.selectFrom(CHARGES_POSTING_ACCOUNT)
                .where(CHARGES_POSTING_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .orderBy(CHARGES_POSTING_ACCOUNT.PURPOSE, CHARGES_POSTING_ACCOUNT.CONCEPT_CATEGORY.asc().nullsFirst())
                .fetch(JooqPostingAccountRepository::toRow);
    }

    @Override
    public Optional<PostingAccountRow> findRow(InstitutionId institution, PostingAccountKey key) {
        Condition category = key.conceptCategory() == null ? CHARGES_POSTING_ACCOUNT.CONCEPT_CATEGORY.isNull()
                : CHARGES_POSTING_ACCOUNT.CONCEPT_CATEGORY.eq(key.conceptCategory());
        return dsl.selectFrom(CHARGES_POSTING_ACCOUNT)
                .where(CHARGES_POSTING_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_POSTING_ACCOUNT.PURPOSE.eq(key.purpose().name())).and(category)
                .forUpdate().fetchOptional().map(JooqPostingAccountRepository::toRow);
    }

    @Override
    public PostingAccountRow insert(InstitutionId institution, UUID id, PostingAccountKey key, UUID ledgerAccountId,
            Instant now) {
        try {
            dsl.insertInto(CHARGES_POSTING_ACCOUNT)
                    .set(CHARGES_POSTING_ACCOUNT.INSTITUTION_ID, institution.value())
                    .set(CHARGES_POSTING_ACCOUNT.ID, id)
                    .set(CHARGES_POSTING_ACCOUNT.PURPOSE, key.purpose().name())
                    .set(CHARGES_POSTING_ACCOUNT.CONCEPT_CATEGORY, key.conceptCategory())
                    .set(CHARGES_POSTING_ACCOUNT.LEDGER_ACCOUNT_ID, ledgerAccountId)
                    .set(CHARGES_POSTING_ACCOUNT.VERSION, 0L)
                    .set(CHARGES_POSTING_ACCOUNT.CREATED_AT, ChargesWrites.toDatabase(now))
                    .set(CHARGES_POSTING_ACCOUNT.UPDATED_AT, ChargesWrites.toDatabase(now))
                    .execute();
        } catch (DataAccessException e) {
            if (isUniqueViolation(e)) {
                // Somebody set the same purpose and category between the read and the insert.
                throw new StaleVersionException();
            }
            throw e;
        }
        return new PostingAccountRow(id, key, ledgerAccountId, 0L, now);
    }

    @Override
    public PostingAccountRow replaceAccount(InstitutionId institution, PostingAccountRow row, UUID ledgerAccountId,
            long expectedVersion, Instant now) {
        long nextVersion = expectedVersion + 1;
        int rows = dsl.update(CHARGES_POSTING_ACCOUNT)
                .set(CHARGES_POSTING_ACCOUNT.LEDGER_ACCOUNT_ID, ledgerAccountId)
                .set(CHARGES_POSTING_ACCOUNT.VERSION, nextVersion)
                .set(CHARGES_POSTING_ACCOUNT.UPDATED_AT, ChargesWrites.toDatabase(now))
                .where(CHARGES_POSTING_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(CHARGES_POSTING_ACCOUNT.ID.eq(row.id()))
                .and(CHARGES_POSTING_ACCOUNT.VERSION.eq(expectedVersion))
                .execute();
        if (rows == 0) {
            boolean exists = dsl.fetchExists(CHARGES_POSTING_ACCOUNT,
                    CHARGES_POSTING_ACCOUNT.INSTITUTION_ID.eq(institution.value())
                            .and(CHARGES_POSTING_ACCOUNT.ID.eq(row.id())));
            throw exists ? new StaleVersionException() : new ResourceNotFoundException();
        }
        return new PostingAccountRow(row.id(), row.key(), ledgerAccountId, nextVersion, now);
    }

    private static PostingAccountRow toRow(ChargesPostingAccountRecord row) {
        return new PostingAccountRow(row.getId(),
                new PostingAccountKey(PostingPurpose.parse(row.getPurpose()), row.getConceptCategory()),
                row.getLedgerAccountId(), row.getVersion(), row.getUpdatedAt().toInstant());
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
