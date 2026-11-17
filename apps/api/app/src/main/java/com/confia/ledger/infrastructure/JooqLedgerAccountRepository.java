package com.confia.ledger.infrastructure;

import static confia.generated.jooq.tables.LedgerAccount.LEDGER_ACCOUNT;

import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.AccountFilter;
import com.confia.ledger.application.AccountPageRequest;
import com.confia.ledger.application.LedgerAccountRepository;
import com.confia.ledger.application.Page;
import com.confia.ledger.domain.AccountType;
import com.confia.ledger.domain.DuplicateAccountCodeException;
import com.confia.ledger.domain.LedgerAccount;
import com.confia.ledger.domain.LedgerResourceNotFoundException;
import com.confia.ledger.domain.StaleAccountVersionException;
import confia.generated.jooq.tables.records.LedgerAccountRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.postgresql.util.PSQLException;

/**
 * The jOOQ adapter of {@link LedgerAccountRepository} over {@code ledger_account}. It inserts, reads and writes only the
 * name, the active flag, the version and the update instant: the code, the type and the student flag are never written after
 * the insert (the database refuses it too) and no statement deletes a row.
 *
 * <p>A write the database refuses becomes a domain error by the {@code SQLState} and the name of the constraint the server
 * reports, never by message text; any other failure is rethrown untouched, so no message of the database reaches a client.
 */
public final class JooqLedgerAccountRepository implements LedgerAccountRepository {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CODE_UNIQUE_INDEX = "ledger_account_code_uq";

    private final DSLContext dsl;

    public JooqLedgerAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<LedgerAccount> findById(InstitutionId institution, UUID id) {
        return dsl.selectFrom(LEDGER_ACCOUNT).where(LEDGER_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_ACCOUNT.ID.eq(id)).fetchOptional().map(JooqLedgerAccountRepository::toDomain);
    }

    @Override
    public Optional<LedgerAccount> lockById(InstitutionId institution, UUID id) {
        return dsl.selectFrom(LEDGER_ACCOUNT).where(LEDGER_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_ACCOUNT.ID.eq(id)).forUpdate().fetchOptional().map(JooqLedgerAccountRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institution, LedgerAccount account) {
        try {
            dsl.insertInto(LEDGER_ACCOUNT)
                    .set(LEDGER_ACCOUNT.INSTITUTION_ID, institution.value())
                    .set(LEDGER_ACCOUNT.ID, account.id())
                    .set(LEDGER_ACCOUNT.CODE, account.code())
                    .set(LEDGER_ACCOUNT.NAME, account.name())
                    .set(LEDGER_ACCOUNT.ACCOUNT_TYPE, account.type().name())
                    .set(LEDGER_ACCOUNT.HOLDS_STUDENT_BALANCES, account.holdsStudentBalances())
                    .set(LEDGER_ACCOUNT.IS_ACTIVE, account.active())
                    .set(LEDGER_ACCOUNT.VERSION, account.version())
                    .set(LEDGER_ACCOUNT.CREATED_AT, toDatabase(account.createdAt()))
                    .set(LEDGER_ACCOUNT.UPDATED_AT, toDatabase(account.updatedAt()))
                    .execute();
        } catch (DataAccessException e) {
            if (CODE_UNIQUE_INDEX.equals(violatedConstraint(e))) {
                throw new DuplicateAccountCodeException();
            }
            throw e;
        }
    }

    @Override
    public void update(InstitutionId institution, LedgerAccount account, long expectedVersion) {
        int rows = dsl.update(LEDGER_ACCOUNT)
                .set(LEDGER_ACCOUNT.NAME, account.name())
                .set(LEDGER_ACCOUNT.IS_ACTIVE, account.active())
                .set(LEDGER_ACCOUNT.VERSION, account.version())
                .set(LEDGER_ACCOUNT.UPDATED_AT, toDatabase(account.updatedAt()))
                .where(LEDGER_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_ACCOUNT.ID.eq(account.id()))
                .and(LEDGER_ACCOUNT.VERSION.eq(expectedVersion))
                .execute();
        if (rows == 0) {
            boolean exists = dsl.fetchExists(LEDGER_ACCOUNT,
                    LEDGER_ACCOUNT.INSTITUTION_ID.eq(institution.value()).and(LEDGER_ACCOUNT.ID.eq(account.id())));
            throw exists ? new StaleAccountVersionException() : new LedgerResourceNotFoundException();
        }
    }

    @Override
    public Page<LedgerAccount> list(InstitutionId institution, AccountPageRequest request, AccountFilter filter) {
        Condition scope = LEDGER_ACCOUNT.INSTITUTION_ID.eq(institution.value());
        if (filter.type() != null) {
            scope = scope.and(LEDGER_ACCOUNT.ACCOUNT_TYPE.eq(filter.type().name()));
        }
        if (filter.active() != null) {
            scope = scope.and(LEDGER_ACCOUNT.IS_ACTIVE.eq(filter.active()));
        }
        long total = dsl.fetchCount(LEDGER_ACCOUNT, scope);
        List<LedgerAccount> rows = dsl.selectFrom(LEDGER_ACCOUNT).where(scope)
                .orderBy(order(request), LEDGER_ACCOUNT.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqLedgerAccountRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(AccountPageRequest request) {
        // Codes and names compare without regard to capitals, whatever the collation of the database.
        Field<String> field = DSL.lower(request.sortField().equals("name") ? LEDGER_ACCOUNT.NAME : LEDGER_ACCOUNT.CODE);
        return request.ascending() ? field.asc() : field.desc();
    }

    private static LedgerAccount toDomain(LedgerAccountRecord row) {
        return new LedgerAccount(row.getId(), row.getCode(), row.getName(), AccountType.parse(row.getAccountType()),
                row.getHoldsStudentBalances(), row.getIsActive(), row.getVersion(), row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime toDatabase(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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
