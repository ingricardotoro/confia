package com.confia.scholarships.infrastructure;

import static confia.generated.jooq.tables.ScholarshipsAwardDecision.SCHOLARSHIPS_AWARD_DECISION;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.application.AwardDecisionRepository;
import com.confia.scholarships.domain.AwardDecision;
import com.confia.scholarships.domain.DecisionKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The jOOQ adapter of {@link AwardDecisionRepository} over {@code scholarships_award_decision}. It inserts and reads: no statement
 * here updates or deletes a decision, and the database refuses them to every role, as it refuses an approval or a rejection by the
 * person who requested the award or on an award that is not in the state the decision acts on.
 */
public final class JooqAwardDecisionRepository implements AwardDecisionRepository {

    private final DSLContext dsl;

    public JooqAwardDecisionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, AwardDecision decision) {
        ScholarshipsWrites.writing(() -> dsl.insertInto(SCHOLARSHIPS_AWARD_DECISION)
                .set(SCHOLARSHIPS_AWARD_DECISION.INSTITUTION_ID, institution.value())
                .set(SCHOLARSHIPS_AWARD_DECISION.ID, decision.id())
                .set(SCHOLARSHIPS_AWARD_DECISION.AWARD_ID, decision.awardId())
                .set(SCHOLARSHIPS_AWARD_DECISION.DECISION, decision.kind().code())
                .set(SCHOLARSHIPS_AWARD_DECISION.REASON, decision.reason())
                .set(SCHOLARSHIPS_AWARD_DECISION.DECIDED_BY, decision.decidedBy())
                .set(SCHOLARSHIPS_AWARD_DECISION.DECIDED_AT, ScholarshipsWrites.toDatabase(decision.decidedAt()))
                .set(SCHOLARSHIPS_AWARD_DECISION.REQUEST_KEY, decision.requestKey())
                .set(SCHOLARSHIPS_AWARD_DECISION.REQUEST_HASH, decision.requestHash())
                .execute());
    }

    @Override
    public Optional<AwardDecision> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(SCHOLARSHIPS_AWARD_DECISION)
                .where(SCHOLARSHIPS_AWARD_DECISION.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_AWARD_DECISION.REQUEST_KEY.eq(requestKey))
                .fetchOptional(JooqAwardDecisionRepository::toDecision);
    }

    @Override
    public List<AwardDecision> findByAward(InstitutionId institution, UUID awardId) {
        return dsl.selectFrom(SCHOLARSHIPS_AWARD_DECISION)
                .where(SCHOLARSHIPS_AWARD_DECISION.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_AWARD_DECISION.AWARD_ID.eq(awardId))
                .orderBy(SCHOLARSHIPS_AWARD_DECISION.DECIDED_AT.asc(), SCHOLARSHIPS_AWARD_DECISION.ID.asc())
                .fetch(JooqAwardDecisionRepository::toDecision);
    }

    private static AwardDecision toDecision(Record row) {
        return new AwardDecision(row.get(SCHOLARSHIPS_AWARD_DECISION.ID), row.get(SCHOLARSHIPS_AWARD_DECISION.AWARD_ID),
                DecisionKind.ofCode(row.get(SCHOLARSHIPS_AWARD_DECISION.DECISION)), row.get(SCHOLARSHIPS_AWARD_DECISION.REASON),
                row.get(SCHOLARSHIPS_AWARD_DECISION.DECIDED_BY),
                ScholarshipsWrites.fromDatabase(row.get(SCHOLARSHIPS_AWARD_DECISION.DECIDED_AT)),
                row.get(SCHOLARSHIPS_AWARD_DECISION.REQUEST_KEY), row.get(SCHOLARSHIPS_AWARD_DECISION.REQUEST_HASH).strip());
    }
}
