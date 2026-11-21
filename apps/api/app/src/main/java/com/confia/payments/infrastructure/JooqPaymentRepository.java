package com.confia.payments.infrastructure;

import static confia.generated.jooq.tables.PaymentsPayment.PAYMENTS_PAYMENT;

import com.confia.kernel.InstitutionId;
import com.confia.payments.application.Page;
import com.confia.payments.application.PageRequest;
import com.confia.payments.application.PaymentFilter;
import com.confia.payments.application.PaymentRepository;
import com.confia.payments.domain.Payment;
import com.confia.payments.domain.PaymentMethod;
import com.confia.payments.domain.PaymentStatus;
import com.confia.payments.domain.TransferReference;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.OrderField;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link PaymentRepository} over {@code payments_payment}. It inserts and reads, and the one update it
 * makes is the forward move of the status: no statement here edits another column or deletes a payment, and the database
 * refuses both to every role, as it refuses a payment whose cash is not in the open session of its cashier (INV-20) or whose
 * amount is not greater than zero (INV-19). Amounts go through {@link MoneyColumns} and nowhere else.
 */
public final class JooqPaymentRepository implements PaymentRepository {

    private final DSLContext dsl;

    public JooqPaymentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, Payment payment) {
        PaymentsWrites.inserting(() -> dsl.insertInto(PAYMENTS_PAYMENT)
                .set(PAYMENTS_PAYMENT.INSTITUTION_ID, institution.value())
                .set(PAYMENTS_PAYMENT.ID, payment.id())
                .set(PAYMENTS_PAYMENT.STUDENT_ID, payment.studentId())
                .set(PAYMENTS_PAYMENT.GUARDIAN_ID, payment.guardianId())
                .set(PAYMENTS_PAYMENT.METHOD, payment.method().name())
                .set(PAYMENTS_PAYMENT.STATUS, payment.status().code())
                .set(PAYMENTS_PAYMENT.AMOUNT, MoneyColumns.amountColumn(payment.amount()))
                .set(PAYMENTS_PAYMENT.CURRENCY, MoneyColumns.currencyColumn(payment.amount()))
                .set(PAYMENTS_PAYMENT.RECEIVED_ON, payment.receivedOn())
                .set(PAYMENTS_PAYMENT.REFERENCE, payment.transfer() == null ? null : payment.transfer().reference())
                .set(PAYMENTS_PAYMENT.BANK_NAME, payment.transfer() == null ? null : payment.transfer().bankName())
                .set(PAYMENTS_PAYMENT.NOTES, payment.notes())
                .set(PAYMENTS_PAYMENT.CASH_SESSION_ID, payment.cashSessionId())
                .set(PAYMENTS_PAYMENT.CASH_MOVEMENT_ID, payment.cashMovementId())
                .set(PAYMENTS_PAYMENT.LEDGER_TRANSACTION_ID, payment.ledgerTransactionId())
                .set(PAYMENTS_PAYMENT.REGISTERED_BY, payment.registeredBy())
                .set(PAYMENTS_PAYMENT.REGISTERED_AT, PaymentsWrites.toDatabase(payment.registeredAt()))
                .set(PAYMENTS_PAYMENT.REQUEST_KEY, payment.requestKey())
                .set(PAYMENTS_PAYMENT.REQUEST_HASH, payment.requestHash())
                .execute());
    }

    @Override
    public Optional<Payment> findById(InstitutionId institution, UUID id) {
        return dsl.selectFrom(PAYMENTS_PAYMENT).where(PAYMENTS_PAYMENT.INSTITUTION_ID.eq(institution.value()))
                .and(PAYMENTS_PAYMENT.ID.eq(id)).fetchOptional(JooqPaymentRepository::toPayment);
    }

    @Override
    public Optional<Payment> findForUpdate(InstitutionId institution, UUID id) {
        return dsl.selectFrom(PAYMENTS_PAYMENT).where(PAYMENTS_PAYMENT.INSTITUTION_ID.eq(institution.value()))
                .and(PAYMENTS_PAYMENT.ID.eq(id)).forUpdate().fetchOptional(JooqPaymentRepository::toPayment);
    }

    @Override
    public Optional<Payment> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(PAYMENTS_PAYMENT).where(PAYMENTS_PAYMENT.INSTITUTION_ID.eq(institution.value()))
                .and(PAYMENTS_PAYMENT.REQUEST_KEY.eq(requestKey)).fetchOptional(JooqPaymentRepository::toPayment);
    }

    @Override
    public boolean transferReferenceInUse(InstitutionId institution, String bankName, String reference) {
        return dsl.fetchExists(PAYMENTS_PAYMENT, PAYMENTS_PAYMENT.INSTITUTION_ID.eq(institution.value())
                .and(PAYMENTS_PAYMENT.METHOD.eq(PaymentMethod.BANK_TRANSFER.name()))
                .and(PAYMENTS_PAYMENT.STATUS.ne(PaymentStatus.REJECTED.code()))
                .and(DSL.lower(PAYMENTS_PAYMENT.BANK_NAME).eq(bankName.toLowerCase(java.util.Locale.ROOT)))
                .and(DSL.lower(PAYMENTS_PAYMENT.REFERENCE).eq(reference.toLowerCase(java.util.Locale.ROOT))));
    }

    @Override
    public void advanceStatus(InstitutionId institution, UUID id, PaymentStatus to) {
        int updated = dsl.update(PAYMENTS_PAYMENT).set(PAYMENTS_PAYMENT.STATUS, to.code())
                .where(PAYMENTS_PAYMENT.INSTITUTION_ID.eq(institution.value())).and(PAYMENTS_PAYMENT.ID.eq(id))
                .execute();
        if (updated != 1) {
            // The caller holds the row locked: anything but one row is a defect, never a decision that silently did nothing.
            throw new IllegalStateException("the status of a payment moves in exactly one row");
        }
    }

    @Override
    public Page<Payment> list(InstitutionId institution, PaymentFilter filter, PageRequest page) {
        Condition where = PAYMENTS_PAYMENT.INSTITUTION_ID.eq(institution.value());
        if (filter.studentId() != null) {
            where = where.and(PAYMENTS_PAYMENT.STUDENT_ID.eq(filter.studentId()));
        }
        if (filter.status() != null) {
            where = where.and(PAYMENTS_PAYMENT.STATUS.eq(filter.status()));
        }
        if (filter.method() != null) {
            where = where.and(PAYMENTS_PAYMENT.METHOD.eq(filter.method()));
        }
        if (filter.registeredBy() != null) {
            where = where.and(PAYMENTS_PAYMENT.REGISTERED_BY.eq(filter.registeredBy()));
        }
        LocalDate from = filter.from();
        if (from != null) {
            where = where.and(PAYMENTS_PAYMENT.RECEIVED_ON.ge(from));
        }
        if (filter.to() != null) {
            where = where.and(PAYMENTS_PAYMENT.RECEIVED_ON.le(filter.to()));
        }
        long total = dsl.selectCount().from(PAYMENTS_PAYMENT).where(where).fetchOne(0, long.class);
        List<Payment> rows = dsl.selectFrom(PAYMENTS_PAYMENT).where(where)
                .orderBy(order(page), PAYMENTS_PAYMENT.ID.asc())
                .limit(page.size()).offset(page.offset())
                .fetch(JooqPaymentRepository::toPayment);
        return new Page<>(rows, total, page.page(), page.size());
    }

    /** The sort field was checked against {@code PaymentFilter.SORT_FIELDS} by the request; anything else is a defect. */
    private static OrderField<?> order(PageRequest page) {
        var field = switch (page.sortField()) {
            case "registeredAt" -> PAYMENTS_PAYMENT.REGISTERED_AT;
            case "receivedOn" -> PAYMENTS_PAYMENT.RECEIVED_ON;
            case "amount" -> PAYMENTS_PAYMENT.AMOUNT;
            default -> throw new IllegalArgumentException("unknown sort field");
        };
        return page.ascending() ? field.asc() : field.desc();
    }

    private static Payment toPayment(Record row) {
        String reference = row.get(PAYMENTS_PAYMENT.REFERENCE);
        TransferReference transfer = reference == null ? null
                : new TransferReference(reference, row.get(PAYMENTS_PAYMENT.BANK_NAME));
        return new Payment(row.get(PAYMENTS_PAYMENT.ID), row.get(PAYMENTS_PAYMENT.STUDENT_ID),
                row.get(PAYMENTS_PAYMENT.GUARDIAN_ID), PaymentMethod.ofName(row.get(PAYMENTS_PAYMENT.METHOD)).orElseThrow(),
                PaymentStatus.ofCode(row.get(PAYMENTS_PAYMENT.STATUS)).orElseThrow(),
                MoneyColumns.toMoney(row.get(PAYMENTS_PAYMENT.AMOUNT), row.get(PAYMENTS_PAYMENT.CURRENCY)),
                row.get(PAYMENTS_PAYMENT.RECEIVED_ON), transfer, row.get(PAYMENTS_PAYMENT.NOTES),
                row.get(PAYMENTS_PAYMENT.CASH_SESSION_ID), row.get(PAYMENTS_PAYMENT.CASH_MOVEMENT_ID),
                row.get(PAYMENTS_PAYMENT.LEDGER_TRANSACTION_ID), row.get(PAYMENTS_PAYMENT.REGISTERED_BY),
                PaymentsWrites.fromDatabase(row.get(PAYMENTS_PAYMENT.REGISTERED_AT)),
                row.get(PAYMENTS_PAYMENT.REQUEST_KEY), row.get(PAYMENTS_PAYMENT.REQUEST_HASH).strip());
    }
}
