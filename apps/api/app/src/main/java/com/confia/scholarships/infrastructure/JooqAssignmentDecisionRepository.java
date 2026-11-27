package com.confia.scholarships.infrastructure;

import static confia.generated.jooq.tables.ScholarshipsDiscountAssignmentDecision.SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.application.AssignmentDecisionRepository;
import com.confia.scholarships.domain.AssignmentDecision;
import com.confia.scholarships.domain.DecisionKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The jOOQ adapter of {@link AssignmentDecisionRepository} over {@code scholarships_discount_assignment_decision}. It inserts and
 * reads: no statement here updates or deletes a decision, and the database refuses them to every role, as it refuses an approval or
 * a rejection by the person who requested the assignment or on an assignment that is not in the state the decision acts on.
 */
public final class JooqAssignmentDecisionRepository implements AssignmentDecisionRepository {

    private final DSLContext dsl;

    public JooqAssignmentDecisionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, AssignmentDecision decision) {
        ScholarshipsWrites.writing(() -> dsl.insertInto(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION)
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.INSTITUTION_ID, institution.value())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.ID, decision.id())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.ASSIGNMENT_ID, decision.assignmentId())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.DECISION, decision.kind().code())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.REASON, decision.reason())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.DECIDED_BY, decision.decidedBy())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.DECIDED_AT, ScholarshipsWrites.toDatabase(decision.decidedAt()))
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.REQUEST_KEY, decision.requestKey())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.REQUEST_HASH, decision.requestHash())
                .execute());
    }

    @Override
    public Optional<AssignmentDecision> findByRequestKey(InstitutionId institution, String requestKey) {
        return dsl.selectFrom(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION)
                .where(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.REQUEST_KEY.eq(requestKey))
                .fetchOptional(JooqAssignmentDecisionRepository::toDecision);
    }

    @Override
    public List<AssignmentDecision> findByAssignment(InstitutionId institution, UUID assignmentId) {
        return dsl.selectFrom(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION)
                .where(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.ASSIGNMENT_ID.eq(assignmentId))
                .orderBy(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.DECIDED_AT.asc(),
                        SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.ID.asc())
                .fetch(JooqAssignmentDecisionRepository::toDecision);
    }

    private static AssignmentDecision toDecision(Record row) {
        return new AssignmentDecision(row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.ID),
                row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.ASSIGNMENT_ID),
                DecisionKind.ofCode(row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.DECISION)),
                row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.REASON),
                row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.DECIDED_BY),
                ScholarshipsWrites.fromDatabase(row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.DECIDED_AT)),
                row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.REQUEST_KEY),
                row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT_DECISION.REQUEST_HASH).strip());
    }
}
