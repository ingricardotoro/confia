package com.confia.scholarships.web;

import com.confia.scholarships.application.AwardView;
import com.confia.scholarships.application.Page;
import com.confia.scholarships.application.ScholarshipView;

/** The output DTO of every route of the scholarships and their awards. Every amount is rounded by the server to the minor unit. */
final class ScholarshipMapper {

    private ScholarshipMapper() {
    }

    static ScholarshipResponse toResponse(ScholarshipView view) {
        return new ScholarshipResponse(view.id(), view.code(), view.name(), view.benefitType(), view.percent(),
                ScholarshipMoney.of(view.fixedAmount()), ScholarshipMoney.of(view.periodCap()),
                view.concepts().stream().map(concept -> new ConceptResponse(concept.id(), concept.code(), concept.name())).toList(),
                view.active(), view.version(), view.benefitLocked(), view.createdAt(), view.updatedAt());
    }

    static ScholarshipPageResponse toResponse(Page<ScholarshipView> page) {
        return new ScholarshipPageResponse(page.items().stream().map(ScholarshipMapper::toResponse).toList(), page.total(),
                page.page(), page.size());
    }

    static AwardResponse toAwardResponse(AwardView view) {
        return new AwardResponse(view.id(), view.scholarshipId(), view.scholarshipCode(), view.scholarshipName(), view.studentId(),
                view.studentCode(), view.academicYearId(), view.validFrom(), view.validTo(), view.status(), view.requestedBy(),
                view.requestedAt(), decision(view.decision()), decision(view.revocation()));
    }

    static AwardPageResponse toAwardResponse(Page<AwardView> page) {
        return new AwardPageResponse(page.items().stream().map(ScholarshipMapper::toAwardResponse).toList(), page.total(),
                page.page(), page.size());
    }

    private static AwardDecisionResponse decision(AwardView.DecisionView decision) {
        return decision == null ? null : new AwardDecisionResponse(decision.id(), decision.kind(), decision.decidedBy(),
                decision.decidedAt(), decision.reason());
    }
}
