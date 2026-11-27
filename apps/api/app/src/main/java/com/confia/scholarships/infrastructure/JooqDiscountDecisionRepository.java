package com.confia.scholarships.infrastructure;

import static confia.generated.jooq.tables.ScholarshipsDiscountChangeDecision.SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.application.DiscountDecisionRepository;
import com.confia.scholarships.domain.DecisionKind;
import com.confia.scholarships.domain.DiscountDecision;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The jOOQ adapter of {@link DiscountDecisionRepository} over {@code scholarships_discount_change_decision}. It inserts and reads: no
 * statement here updates or deletes a decision, and the database refuses them to every role, as it refuses a decision by the person
 * who requested the discount or the proposal, on a discount that waits for nothing, or for another version than the current one.
 */
public final class JooqDiscountDecisionRepository implements DiscountDecisionRepository {

    private final DSLContext dsl;

    public JooqDiscountDecisionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, DiscountDecision decision) {
        ScholarshipsWrites.writing(() -> dsl.insertInto(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION)
                .set(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.INSTITUTION_ID, institution.value())
                .set(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.ID, decision.id())
                .set(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.DISCOUNT_ID, decision.discountId())
                .set(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.DISCOUNT_VERSION, decision.discountVersion())
                .set(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.DECISION, decision.kind().code())
                .set(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.REASON, decision.reason())
                .set(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.DECIDED_BY, decision.decidedBy())
                .set(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.DECIDED_AT, ScholarshipsWrites.toDatabase(decision.decidedAt()))
                .set(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.REQUEST_KEY, decision.requestKey())
                .set(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.REQUEST_HASH, decision.requestHash())
                .execute());
    }

    @Override
    public Optional<DiscountDecision> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION)
                .where(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.REQUEST_KEY.eq(requestKey))
                .fetchOptional(JooqDiscountDecisionRepository::toDecision);
    }

    private static DiscountDecision toDecision(Record row) {
        return new DiscountDecision(row.get(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.ID),
                row.get(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.DISCOUNT_ID),
                row.get(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.DISCOUNT_VERSION),
                DecisionKind.ofCode(row.get(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.DECISION)),
                row.get(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.REASON),
                row.get(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.DECIDED_BY),
                ScholarshipsWrites.fromDatabase(row.get(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.DECIDED_AT)),
                row.get(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.REQUEST_KEY),
                row.get(SCHOLARSHIPS_DISCOUNT_CHANGE_DECISION.REQUEST_HASH).strip());
    }
}
