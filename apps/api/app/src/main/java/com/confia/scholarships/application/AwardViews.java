package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.AwardDecision;
import com.confia.scholarships.domain.DecisionKind;
import com.confia.scholarships.domain.Scholarship;
import com.confia.scholarships.domain.ScholarshipAward;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the views of the awards: the code and the name of the scholarship, the code of the student (never its name or its
 * documents) and, for one award, the decisions on it. It runs inside the transaction of the use case that asks.
 */
final class AwardViews {

    private final ScholarshipRepository scholarships;
    private final AwardDecisionRepository decisions;
    private final StudentDirectory students;

    AwardViews(ScholarshipRepository scholarships, AwardDecisionRepository decisions, StudentDirectory students) {
        this.scholarships = Objects.requireNonNull(scholarships, "scholarships");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.students = Objects.requireNonNull(students, "students");
    }

    /** One award with its decisions. */
    AwardView detail(InstitutionId institution, ScholarshipAward award) {
        List<AwardDecision> made = decisions.findByAward(institution, award.id());
        AwardDecision first = made.stream().filter(decision -> decision.kind() != DecisionKind.REVOKED).findFirst().orElse(null);
        AwardDecision revocation = made.stream().filter(decision -> decision.kind() == DecisionKind.REVOKED).findFirst()
                .orElse(null);
        Scholarship scholarship = scholarships.findById(institution, award.scholarshipId()).orElse(null);
        StudentRef student = students.find(institution, award.studentId()).orElse(null);
        return AwardView.of(award, scholarship == null ? null : scholarship.code(), scholarship == null ? null : scholarship.name(),
                student == null ? null : student.studentCode(), first, revocation);
    }

    /** A page of awards, without their decisions: the scholarships and the students are read once each. */
    Page<AwardView> page(InstitutionId institution, Page<ScholarshipAward> found) {
        Set<UUID> scholarshipIds = new HashSet<>();
        Set<UUID> studentIds = new HashSet<>();
        found.items().forEach(award -> {
            scholarshipIds.add(award.scholarshipId());
            studentIds.add(award.studentId());
        });
        Map<UUID, Scholarship> byScholarship = scholarshipIds.isEmpty() ? Map.of() : scholarships.findAll(institution, scholarshipIds);
        Map<UUID, StudentRef> byStudent = studentIds.isEmpty() ? Map.of() : students.findAll(institution, studentIds);
        return found.map(award -> {
            Scholarship scholarship = byScholarship.get(award.scholarshipId());
            StudentRef student = byStudent.get(award.studentId());
            return AwardView.of(award, scholarship == null ? null : scholarship.code(),
                    scholarship == null ? null : scholarship.name(), student == null ? null : student.studentCode(), null, null);
        });
    }
}
