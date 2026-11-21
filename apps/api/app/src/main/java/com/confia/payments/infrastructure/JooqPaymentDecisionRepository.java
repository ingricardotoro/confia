package com.confia.payments.infrastructure;

import static confia.generated.jooq.tables.PaymentsPaymentDecision.PAYMENTS_PAYMENT_DECISION;

import com.confia.kernel.InstitutionId;
import com.confia.payments.application.PaymentDecisionRepository;
import com.confia.payments.domain.DecisionKind;
import com.confia.payments.domain.PaymentDecision;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The jOOQ adapter of {@link PaymentDecisionRepository} over {@code payments_payment_decision}. It inserts and reads: no statement
 * here updates or deletes a decision, and the database refuses them to every role, as it refuses a decision by the person who
 * registered the payment or on a payment that is not a declared transfer.
 */
public final class JooqPaymentDecisionRepository implements PaymentDecisionRepository {

    private final DSLContext dsl;

    public JooqPaymentDecisionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, PaymentDecision decision) {
        PaymentsWrites.inserting(() -> dsl.insertInto(PAYMENTS_PAYMENT_DECISION)
                .set(PAYMENTS_PAYMENT_DECISION.INSTITUTION_ID, institution.value())
                .set(PAYMENTS_PAYMENT_DECISION.ID, decision.id())
                .set(PAYMENTS_PAYMENT_DECISION.PAYMENT_ID, decision.paymentId())
                .set(PAYMENTS_PAYMENT_DECISION.DECISION, decision.kind().code())
                .set(PAYMENTS_PAYMENT_DECISION.REASON, decision.reason())
                .set(PAYMENTS_PAYMENT_DECISION.LEDGER_TRANSACTION_ID, decision.ledgerTransactionId())
                .set(PAYMENTS_PAYMENT_DECISION.DECIDED_BY, decision.decidedBy())
                .set(PAYMENTS_PAYMENT_DECISION.DECIDED_AT, PaymentsWrites.toDatabase(decision.decidedAt()))
                .set(PAYMENTS_PAYMENT_DECISION.REQUEST_KEY, decision.requestKey())
                .set(PAYMENTS_PAYMENT_DECISION.REQUEST_HASH, decision.requestHash())
                .execute());
    }

    @Override
    public Optional<PaymentDecision> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(PAYMENTS_PAYMENT_DECISION)
                .where(PAYMENTS_PAYMENT_DECISION.INSTITUTION_ID.eq(institution.value()))
                .and(PAYMENTS_PAYMENT_DECISION.REQUEST_KEY.eq(requestKey))
                .fetchOptional(JooqPaymentDecisionRepository::toDecision);
    }

    @Override
    public Optional<PaymentDecision> findByPayment(InstitutionId institution, UUID paymentId) {
        return dsl.selectFrom(PAYMENTS_PAYMENT_DECISION)
                .where(PAYMENTS_PAYMENT_DECISION.INSTITUTION_ID.eq(institution.value()))
                .and(PAYMENTS_PAYMENT_DECISION.PAYMENT_ID.eq(paymentId))
                .fetchOptional(JooqPaymentDecisionRepository::toDecision);
    }

    private static PaymentDecision toDecision(Record row) {
        return new PaymentDecision(row.get(PAYMENTS_PAYMENT_DECISION.ID), row.get(PAYMENTS_PAYMENT_DECISION.PAYMENT_ID),
                DecisionKind.ofCode(row.get(PAYMENTS_PAYMENT_DECISION.DECISION)),
                row.get(PAYMENTS_PAYMENT_DECISION.REASON), row.get(PAYMENTS_PAYMENT_DECISION.LEDGER_TRANSACTION_ID),
                row.get(PAYMENTS_PAYMENT_DECISION.DECIDED_BY),
                PaymentsWrites.fromDatabase(row.get(PAYMENTS_PAYMENT_DECISION.DECIDED_AT)),
                row.get(PAYMENTS_PAYMENT_DECISION.REQUEST_KEY), row.get(PAYMENTS_PAYMENT_DECISION.REQUEST_HASH).strip());
    }
}
