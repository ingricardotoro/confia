package com.confia.ledger.infrastructure;

import static confia.generated.jooq.tables.LedgerAccount.LEDGER_ACCOUNT;
import static confia.generated.jooq.tables.LedgerEntry.LEDGER_ENTRY;
import static confia.generated.jooq.tables.LedgerStudentAccount.LEDGER_STUDENT_ACCOUNT;
import static confia.generated.jooq.tables.LedgerTransaction.LEDGER_TRANSACTION;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.ConcurrentIdempotentPostException;
import com.confia.ledger.application.EntryView;
import com.confia.ledger.application.LedgerRepository;
import com.confia.ledger.application.Page;
import com.confia.ledger.application.StoredTransaction;
import com.confia.ledger.application.StudentAccountRef;
import com.confia.ledger.application.StudentCurrency;
import com.confia.ledger.application.api.LedgerStatementApi;
import com.confia.ledger.domain.AccountRef;
import com.confia.ledger.domain.EntrySide;
import com.confia.ledger.domain.LedgerResourceNotFoundException;
import com.confia.ledger.domain.TransactionAlreadyReversedException;
import com.confia.ledger.domain.TransactionKind;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record3;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.postgresql.util.PSQLException;

/**
 * The jOOQ adapter of {@link LedgerRepository} over the tables of the ledger. It inserts and reads. The only statement
 * that is not an insert or a plain read is {@code SELECT ... FOR UPDATE} on a student sub-account; no statement here
 * updates or deletes a transaction or an entry, and the database refuses them to every role (INV-04). Amounts go through
 * {@link MoneyColumns} and nowhere else; the balance is summed by the database, never stored.
 */
public final class JooqLedgerRepository implements LedgerRepository {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String KEY_UNIQUE_INDEX = "ledger_transaction_idempotency_uq";
    private static final String REVERSAL_UNIQUE_INDEX = "ledger_transaction_single_reversal_uq";

    private final DSLContext dsl;

    public JooqLedgerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<StoredTransaction> findByIdempotencyKey(InstitutionId institution, String key) {
        return dsl.selectFrom(LEDGER_TRANSACTION).where(LEDGER_TRANSACTION.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_TRANSACTION.IDEMPOTENCY_KEY.eq(key)).fetchOptional()
                .map(row -> toStored(institution, row));
    }

    @Override
    public Optional<StoredTransaction> findById(InstitutionId institution, UUID id) {
        return dsl.selectFrom(LEDGER_TRANSACTION).where(LEDGER_TRANSACTION.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_TRANSACTION.ID.eq(id)).fetchOptional().map(row -> toStored(institution, row));
    }

    @Override
    public boolean isReversed(InstitutionId institution, UUID transactionId) {
        return dsl.fetchExists(dsl.selectOne().from(LEDGER_TRANSACTION)
                .where(LEDGER_TRANSACTION.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_TRANSACTION.REVERSES_TRANSACTION_ID.eq(transactionId)));
    }

    @Override
    public List<AccountRef> findAccounts(InstitutionId institution, Set<UUID> accountIds) {
        return dsl.selectFrom(LEDGER_ACCOUNT).where(LEDGER_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_ACCOUNT.ID.in(accountIds))
                .fetch(row -> new AccountRef(row.getId(), row.getHoldsStudentBalances(), row.getIsActive()));
    }

    @Override
    public List<com.confia.ledger.application.AccountDetails> findAccountDetails(InstitutionId institution,
            Set<UUID> accountIds) {
        return dsl.selectFrom(LEDGER_ACCOUNT).where(LEDGER_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_ACCOUNT.ID.in(accountIds))
                .fetch(row -> new com.confia.ledger.application.AccountDetails(row.getId(), row.getCode(),
                        row.getName(), row.getAccountType(), row.getHoldsStudentBalances(), row.getIsActive()));
    }

    @Override
    public List<com.confia.ledger.application.AccountDetails> lockShareAccountDetails(InstitutionId institution,
            Set<UUID> accountIds) {
        return dsl.selectFrom(LEDGER_ACCOUNT).where(LEDGER_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_ACCOUNT.ID.in(accountIds)).orderBy(LEDGER_ACCOUNT.ID.asc()).forShare()
                .fetch(row -> new com.confia.ledger.application.AccountDetails(row.getId(), row.getCode(),
                        row.getName(), row.getAccountType(), row.getHoldsStudentBalances(), row.getIsActive()));
    }

    @Override
    public List<StudentAccountRef> openAndLock(InstitutionId institution, List<StudentCurrency> keys) {
        List<StudentAccountRef> result = new ArrayList<>();
        for (StudentCurrency key : keys) {
            int opened = 0;
            try {
                opened = dsl.insertInto(LEDGER_STUDENT_ACCOUNT)
                        .set(LEDGER_STUDENT_ACCOUNT.INSTITUTION_ID, institution.value())
                        .set(LEDGER_STUDENT_ACCOUNT.ID, UUID.randomUUID())
                        .set(LEDGER_STUDENT_ACCOUNT.STUDENT_ID, key.studentId())
                        .set(LEDGER_STUDENT_ACCOUNT.CURRENCY, key.currency().name())
                        .onConflictDoNothing().execute();
            } catch (DataAccessException e) {
                if ("ledger_student_account_student_fk".equals(violatedConstraint(e, "23503"))) {
                    throw new LedgerResourceNotFoundException();
                }
                throw e;
            }
            var row = dsl.selectFrom(LEDGER_STUDENT_ACCOUNT)
                    .where(LEDGER_STUDENT_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                    .and(LEDGER_STUDENT_ACCOUNT.STUDENT_ID.eq(key.studentId()))
                    .and(LEDGER_STUDENT_ACCOUNT.CURRENCY.eq(key.currency().name()))
                    .forUpdate().fetchOne();
            result.add(new StudentAccountRef(row.getId(), key, row.getStatus(), opened == 1));
        }
        return result;
    }

    @Override
    public void insert(InstitutionId institution, com.confia.ledger.domain.LedgerTransaction transaction,
            String payloadHash, Map<StudentCurrency, UUID> accounts) {
        try {
            dsl.insertInto(LEDGER_TRANSACTION)
                    .set(LEDGER_TRANSACTION.INSTITUTION_ID, institution.value())
                    .set(LEDGER_TRANSACTION.ID, transaction.id())
                    .set(LEDGER_TRANSACTION.KIND, transaction.kind().name())
                    .set(LEDGER_TRANSACTION.EFFECTIVE_ON, transaction.effectiveOn())
                    // Overwritten by the database (INV-42): the value sent is irrelevant.
                    .set(LEDGER_TRANSACTION.POSTING_XID, 0L)
                    .set(LEDGER_TRANSACTION.SOURCE_MODULE, transaction.sourceModule())
                    .set(LEDGER_TRANSACTION.SOURCE_REF_ID, transaction.sourceRefId())
                    .set(LEDGER_TRANSACTION.REVERSES_TRANSACTION_ID, transaction.reversesTransactionId())
                    .set(LEDGER_TRANSACTION.REASON, transaction.reason())
                    .set(LEDGER_TRANSACTION.IDEMPOTENCY_KEY, transaction.idempotencyKey())
                    .set(LEDGER_TRANSACTION.PAYLOAD_HASH, payloadHash)
                    .set(LEDGER_TRANSACTION.CREATED_BY, transaction.createdBy())
                    .execute();
        } catch (DataAccessException e) {
            String unique = violatedConstraint(e, UNIQUE_VIOLATION);
            if (KEY_UNIQUE_INDEX.equals(unique)) {
                throw new ConcurrentIdempotentPostException();
            }
            if (REVERSAL_UNIQUE_INDEX.equals(unique)) {
                throw new TransactionAlreadyReversedException();
            }
            throw e;
        }
        int line = 1;
        for (com.confia.ledger.domain.LedgerEntry entry : transaction.entries()) {
            UUID studentAccount = entry.studentId() == null ? null
                    : accounts.get(new StudentCurrency(entry.studentId(), entry.amount().currency()));
            dsl.insertInto(LEDGER_ENTRY)
                    .set(LEDGER_ENTRY.INSTITUTION_ID, institution.value())
                    .set(LEDGER_ENTRY.ID, UUID.randomUUID())
                    .set(LEDGER_ENTRY.TRANSACTION_ID, transaction.id())
                    .set(LEDGER_ENTRY.LINE_NUMBER, line++)
                    .set(LEDGER_ENTRY.ACCOUNT_ID, entry.accountId())
                    .set(LEDGER_ENTRY.STUDENT_ACCOUNT_ID, studentAccount)
                    .set(LEDGER_ENTRY.SIDE, entry.side().name())
                    .set(LEDGER_ENTRY.AMOUNT, MoneyColumns.amountColumn(entry.amount()))
                    .set(LEDGER_ENTRY.CURRENCY, MoneyColumns.currencyColumn(entry.amount()))
                    .execute();
        }
    }

    @Override
    public Money balance(InstitutionId institution, UUID studentId, CurrencyCode currency, LocalDate asOf,
            Instant knownAt) {
        Field<BigDecimal> signed = DSL.when(LEDGER_ENTRY.SIDE.eq(EntrySide.DEBIT.name()), LEDGER_ENTRY.AMOUNT)
                .otherwise(LEDGER_ENTRY.AMOUNT.neg());
        Condition where = LEDGER_ENTRY.INSTITUTION_ID.eq(institution.value())
                .and(LEDGER_STUDENT_ACCOUNT.STUDENT_ID.eq(studentId))
                .and(LEDGER_STUDENT_ACCOUNT.CURRENCY.eq(currency.name()))
                .and(LEDGER_TRANSACTION.EFFECTIVE_ON.le(asOf));
        if (knownAt != null) {
            where = where.and(LEDGER_TRANSACTION.POSTED_AT.le(OffsetDateTime.ofInstant(knownAt, ZoneOffset.UTC)));
        }
        BigDecimal sum = dsl.select(DSL.sum(signed)).from(LEDGER_ENTRY)
                .join(LEDGER_STUDENT_ACCOUNT)
                .on(LEDGER_STUDENT_ACCOUNT.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                .and(LEDGER_STUDENT_ACCOUNT.ID.eq(LEDGER_ENTRY.STUDENT_ACCOUNT_ID))
                .join(LEDGER_TRANSACTION)
                .on(LEDGER_TRANSACTION.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                .and(LEDGER_TRANSACTION.ID.eq(LEDGER_ENTRY.TRANSACTION_ID))
                .where(where).fetchOne(0, BigDecimal.class);
        return sum == null ? Money.zero(currency) : MoneyColumns.toMoney(sum, currency.name());
    }

    @Override
    public Page<EntryView> listEntries(InstitutionId institution, UUID studentId, CurrencyCode currency, int page,
            int size) {
        Condition where = LEDGER_ENTRY.INSTITUTION_ID.eq(institution.value())
                .and(LEDGER_STUDENT_ACCOUNT.STUDENT_ID.eq(studentId))
                .and(LEDGER_STUDENT_ACCOUNT.CURRENCY.eq(currency.name()));
        var joined = dsl.select().from(LEDGER_ENTRY)
                .join(LEDGER_STUDENT_ACCOUNT)
                .on(LEDGER_STUDENT_ACCOUNT.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                .and(LEDGER_STUDENT_ACCOUNT.ID.eq(LEDGER_ENTRY.STUDENT_ACCOUNT_ID))
                .join(LEDGER_TRANSACTION)
                .on(LEDGER_TRANSACTION.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                .and(LEDGER_TRANSACTION.ID.eq(LEDGER_ENTRY.TRANSACTION_ID));
        Long total = dsl.selectCount().from(LEDGER_ENTRY)
                .join(LEDGER_STUDENT_ACCOUNT)
                .on(LEDGER_STUDENT_ACCOUNT.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                .and(LEDGER_STUDENT_ACCOUNT.ID.eq(LEDGER_ENTRY.STUDENT_ACCOUNT_ID))
                .where(where).fetchOne(0, Long.class);
        List<EntryView> items = joined.where(where)
                .orderBy(LEDGER_TRANSACTION.EFFECTIVE_ON.asc(), LEDGER_TRANSACTION.POSTED_AT.asc(),
                        LEDGER_TRANSACTION.ID.asc(), LEDGER_ENTRY.LINE_NUMBER.asc())
                .limit(size).offset(page * size)
                .fetch(row -> toView(row));
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    @Override
    public List<CurrencyCode> studentCurrencies(InstitutionId institution, UUID studentId) {
        return dsl.selectDistinct(LEDGER_STUDENT_ACCOUNT.CURRENCY).from(LEDGER_STUDENT_ACCOUNT)
                .where(LEDGER_STUDENT_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_STUDENT_ACCOUNT.STUDENT_ID.eq(studentId))
                .orderBy(LEDGER_STUDENT_ACCOUNT.CURRENCY.asc())
                .fetch(row -> CurrencyCode.valueOf(row.get(LEDGER_STUDENT_ACCOUNT.CURRENCY).trim()));
    }

    @Override
    public LedgerStatementApi.Section statement(InstitutionId institution, UUID studentId, CurrencyCode currency,
            LocalDate from, LocalDate to, int page, int size) {
        Field<BigDecimal> signed = DSL.when(LEDGER_ENTRY.SIDE.eq(EntrySide.DEBIT.name()), LEDGER_ENTRY.AMOUNT)
                .otherwise(LEDGER_ENTRY.AMOUNT.neg());
        Condition ofAccount = LEDGER_ENTRY.INSTITUTION_ID.eq(institution.value())
                .and(LEDGER_STUDENT_ACCOUNT.STUDENT_ID.eq(studentId))
                .and(LEDGER_STUDENT_ACCOUNT.CURRENCY.eq(currency.name()));
        Condition before = LEDGER_TRANSACTION.EFFECTIVE_ON.lt(from);
        Condition inPeriod = LEDGER_TRANSACTION.EFFECTIVE_ON.ge(from).and(LEDGER_TRANSACTION.EFFECTIVE_ON.le(to));

        // One statement, one snapshot: the opening balance, the net of the period and the count of its lines.
        Record3<BigDecimal, BigDecimal, Integer> totals = dsl
                .select(DSL.sum(signed).filterWhere(before), DSL.sum(signed).filterWhere(inPeriod),
                        DSL.count().filterWhere(inPeriod))
                .from(LEDGER_ENTRY)
                .join(LEDGER_STUDENT_ACCOUNT)
                .on(LEDGER_STUDENT_ACCOUNT.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                .and(LEDGER_STUDENT_ACCOUNT.ID.eq(LEDGER_ENTRY.STUDENT_ACCOUNT_ID))
                .join(LEDGER_TRANSACTION)
                .on(LEDGER_TRANSACTION.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                .and(LEDGER_TRANSACTION.ID.eq(LEDGER_ENTRY.TRANSACTION_ID))
                .where(ofAccount.and(LEDGER_TRANSACTION.EFFECTIVE_ON.le(to)))
                .fetchOne();
        Money opening = money(totals.value1(), currency);
        Money net = money(totals.value2(), currency);
        long totalLines = totals.value3() == null ? 0 : totals.value3();

        // The running balance is the opening balance plus the cumulative signed amount in the canonical order; the opening
        // balance is a scalar subquery of the same statement, so a page is consistent in itself.
        Field<BigDecimal> openingField = DSL.coalesce(
                dsl.select(DSL.sum(signed)).from(LEDGER_ENTRY)
                        .join(LEDGER_STUDENT_ACCOUNT)
                        .on(LEDGER_STUDENT_ACCOUNT.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                        .and(LEDGER_STUDENT_ACCOUNT.ID.eq(LEDGER_ENTRY.STUDENT_ACCOUNT_ID))
                        .join(LEDGER_TRANSACTION)
                        .on(LEDGER_TRANSACTION.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                        .and(LEDGER_TRANSACTION.ID.eq(LEDGER_ENTRY.TRANSACTION_ID))
                        .where(ofAccount.and(before)).asField(),
                BigDecimal.ZERO);
        Field<BigDecimal> running = openingField.add(DSL.sum(signed).over()
                .orderBy(LEDGER_TRANSACTION.EFFECTIVE_ON, LEDGER_TRANSACTION.POSTED_AT, LEDGER_TRANSACTION.ID,
                        LEDGER_ENTRY.LINE_NUMBER)
                .rowsBetweenUnboundedPreceding().andCurrentRow()).as("running_balance");
        Money zero = Money.zero(currency);
        List<LedgerStatementApi.Line> lines = dsl
                .select(LEDGER_TRANSACTION.ID, LEDGER_TRANSACTION.KIND, LEDGER_TRANSACTION.EFFECTIVE_ON,
                        LEDGER_TRANSACTION.POSTED_AT, LEDGER_ENTRY.LINE_NUMBER, LEDGER_TRANSACTION.SOURCE_MODULE,
                        LEDGER_TRANSACTION.REASON, LEDGER_TRANSACTION.REVERSES_TRANSACTION_ID, LEDGER_ENTRY.SIDE,
                        LEDGER_ENTRY.AMOUNT, LEDGER_ENTRY.CURRENCY, running)
                .from(LEDGER_ENTRY)
                .join(LEDGER_STUDENT_ACCOUNT)
                .on(LEDGER_STUDENT_ACCOUNT.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                .and(LEDGER_STUDENT_ACCOUNT.ID.eq(LEDGER_ENTRY.STUDENT_ACCOUNT_ID))
                .join(LEDGER_TRANSACTION)
                .on(LEDGER_TRANSACTION.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                .and(LEDGER_TRANSACTION.ID.eq(LEDGER_ENTRY.TRANSACTION_ID))
                .where(ofAccount.and(inPeriod))
                .orderBy(LEDGER_TRANSACTION.EFFECTIVE_ON.asc(), LEDGER_TRANSACTION.POSTED_AT.asc(),
                        LEDGER_TRANSACTION.ID.asc(), LEDGER_ENTRY.LINE_NUMBER.asc())
                .limit(size).offset((long) page * size)
                .fetch(row -> {
                    Money amount = MoneyColumns.toMoney(row.get(LEDGER_ENTRY.AMOUNT), row.get(LEDGER_ENTRY.CURRENCY));
                    boolean debit = EntrySide.DEBIT.name().equals(row.get(LEDGER_ENTRY.SIDE));
                    return new LedgerStatementApi.Line(row.get(LEDGER_TRANSACTION.ID),
                            row.get(LEDGER_TRANSACTION.KIND), row.get(LEDGER_TRANSACTION.EFFECTIVE_ON),
                            row.get(LEDGER_TRANSACTION.POSTED_AT).toInstant(), row.get(LEDGER_ENTRY.LINE_NUMBER),
                            row.get(LEDGER_TRANSACTION.SOURCE_MODULE), row.get(LEDGER_TRANSACTION.REASON),
                            row.get(LEDGER_TRANSACTION.REVERSES_TRANSACTION_ID), debit ? amount : zero,
                            debit ? zero : amount, money(row.get(running), currency));
                });
        return new LedgerStatementApi.Section(currency, opening, opening.add(net), totalLines, page, size, lines);
    }

    private static Money money(BigDecimal sum, CurrencyCode currency) {
        return sum == null ? Money.zero(currency) : MoneyColumns.toMoney(sum, currency.name());
    }

    private static EntryView toView(Record row) {
        return new EntryView(row.get(LEDGER_TRANSACTION.ID), TransactionKind.valueOf(row.get(LEDGER_TRANSACTION.KIND)),
                row.get(LEDGER_TRANSACTION.EFFECTIVE_ON), row.get(LEDGER_TRANSACTION.POSTED_AT).toInstant(),
                row.get(LEDGER_ENTRY.LINE_NUMBER), row.get(LEDGER_ENTRY.ACCOUNT_ID),
                EntrySide.valueOf(row.get(LEDGER_ENTRY.SIDE)),
                MoneyColumns.toMoney(row.get(LEDGER_ENTRY.AMOUNT), row.get(LEDGER_ENTRY.CURRENCY)),
                row.get(LEDGER_TRANSACTION.SOURCE_MODULE), row.get(LEDGER_TRANSACTION.REVERSES_TRANSACTION_ID));
    }

    private StoredTransaction toStored(InstitutionId institution,
            confia.generated.jooq.tables.records.LedgerTransactionRecord header) {
        var student = LEDGER_STUDENT_ACCOUNT;
        List<com.confia.ledger.domain.LedgerEntry> entries = dsl.select().from(LEDGER_ENTRY)
                .leftJoin(student).on(student.INSTITUTION_ID.eq(LEDGER_ENTRY.INSTITUTION_ID))
                .and(student.ID.eq(LEDGER_ENTRY.STUDENT_ACCOUNT_ID))
                .where(LEDGER_ENTRY.INSTITUTION_ID.eq(institution.value()))
                .and(LEDGER_ENTRY.TRANSACTION_ID.eq(header.getId()))
                .orderBy(LEDGER_ENTRY.LINE_NUMBER.asc())
                .fetch(row -> new com.confia.ledger.domain.LedgerEntry(row.get(LEDGER_ENTRY.ACCOUNT_ID),
                        row.get(student.STUDENT_ID), EntrySide.valueOf(row.get(LEDGER_ENTRY.SIDE)),
                        MoneyColumns.toMoney(row.get(LEDGER_ENTRY.AMOUNT), row.get(LEDGER_ENTRY.CURRENCY))));
        var transaction = com.confia.ledger.domain.LedgerTransaction.restore(header.getId(),
                TransactionKind.valueOf(header.getKind()), header.getEffectiveOn(), header.getSourceModule(),
                header.getSourceRefId(), header.getReversesTransactionId(), header.getReason(),
                header.getIdempotencyKey(), header.getCreatedBy(), entries);
        return new StoredTransaction(transaction, header.getPayloadHash(), header.getPostedAt().toInstant());
    }

    private static String violatedConstraint(Throwable failure, String sqlState) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres && sqlState.equals(postgres.getSQLState())
                    && postgres.getServerErrorMessage() != null) {
                return postgres.getServerErrorMessage().getConstraint();
            }
        }
        return null;
    }
}
