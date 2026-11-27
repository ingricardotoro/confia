package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.AssignmentDecision;
import com.confia.scholarships.domain.DecisionKind;
import com.confia.scholarships.domain.Discount;
import com.confia.scholarships.domain.DiscountAssignment;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the views of the discount assignments: the code and the name of the discount, the code of the student (never its name or its
 * documents) and, for one assignment, the decisions on it. It runs inside the transaction of the use case that asks.
 */
final class AssignmentViews {

    private final DiscountRepository discounts;
    private final AssignmentDecisionRepository decisions;
    private final StudentDirectory students;

    AssignmentViews(DiscountRepository discounts, AssignmentDecisionRepository decisions, StudentDirectory students) {
        this.discounts = Objects.requireNonNull(discounts, "discounts");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.students = Objects.requireNonNull(students, "students");
    }

    /** One assignment with its decisions. */
    AssignmentView detail(InstitutionId institution, DiscountAssignment assignment) {
        List<AssignmentDecision> made = decisions.findByAssignment(institution, assignment.id());
        AssignmentDecision first = made.stream().filter(decision -> decision.kind() != DecisionKind.REVOKED).findFirst()
                .orElse(null);
        AssignmentDecision revocation = made.stream().filter(decision -> decision.kind() == DecisionKind.REVOKED).findFirst()
                .orElse(null);
        Discount discount = discounts.findById(institution, assignment.discountId()).orElse(null);
        StudentRef student = students.find(institution, assignment.studentId()).orElse(null);
        return AssignmentView.of(assignment, discount == null ? null : discount.code(), discount == null ? null : discount.name(),
                student == null ? null : student.studentCode(), first, revocation);
    }

    /** A page of assignments, without their decisions: the discounts and the students are read once each. */
    Page<AssignmentView> page(InstitutionId institution, Page<DiscountAssignment> found) {
        Set<UUID> discountIds = new HashSet<>();
        Set<UUID> studentIds = new HashSet<>();
        found.items().forEach(assignment -> {
            discountIds.add(assignment.discountId());
            studentIds.add(assignment.studentId());
        });
        Map<UUID, Discount> byDiscount = discountIds.isEmpty() ? Map.of() : discounts.findAll(institution, discountIds);
        Map<UUID, StudentRef> byStudent = studentIds.isEmpty() ? Map.of() : students.findAll(institution, studentIds);
        return found.map(assignment -> {
            Discount discount = byDiscount.get(assignment.discountId());
            StudentRef student = byStudent.get(assignment.studentId());
            return AssignmentView.of(assignment, discount == null ? null : discount.code(),
                    discount == null ? null : discount.name(), student == null ? null : student.studentCode(), null, null);
        });
    }
}
