package com.confia.students.application;

import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.students.domain.Enrollment;
import com.confia.students.domain.EnrollmentTransfer;
import java.util.UUID;

/**
 * The entries of the enrollments: {@code students.enrollment.created}, {@code students.enrollment.transferred}
 * and {@code students.enrollment.exited}, built with {@link AuditJson} and never by concatenation. The audit
 * trail is kept seven years and never erased, so it holds no personal data of the minor: no name and no birth
 * date. It holds the student code, the identifiers of the section and the year, the status and the dates of
 * the enrollment. The free text of a reason is never copied: it appears only as «present» (a boolean).
 */
final class EnrollmentsAudit {

    static final String ENROLLMENT = "students.enrollment";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private EnrollmentsAudit() {
    }

    static AuditEntry created(AuthenticatedActor actor, UUID requestId, String studentCode, Enrollment after) {
        return entry(actor, requestId, ENROLLMENT + ".created", after.id(), null, json(studentCode, after).toJson());
    }

    static AuditEntry transferred(AuthenticatedActor actor, UUID requestId, String studentCode, Enrollment before,
            Enrollment after, EnrollmentTransfer transfer) {
        AuditJson.Builder state = json(studentCode, after).text("transferredOn", transfer.transferredOn().toString())
                .bool("hasReason", transfer.reason() != null);
        return entry(actor, requestId, ENROLLMENT + ".transferred", after.id(), json(studentCode, before).toJson(),
                state.toJson());
    }

    static AuditEntry exited(AuthenticatedActor actor, UUID requestId, String studentCode, Enrollment before,
            Enrollment after) {
        AuditJson.Builder state = json(studentCode, after).text("withdrawnOn", after.withdrawnOn().toString())
                .bool("hasWithdrawalReason", after.withdrawalReason() != null);
        return entry(actor, requestId, ENROLLMENT + ".exited", after.id(), json(studentCode, before).toJson(),
                state.toJson());
    }

    private static AuditJson.Builder json(String studentCode, Enrollment enrollment) {
        return AuditJson.object().text("studentCode", studentCode)
                .text("sectionId", enrollment.sectionId().toString())
                .text("academicYearId", enrollment.academicYearId().toString())
                .text("enrolledOn", enrollment.enrolledOn().toString())
                .text("status", enrollment.status().name());
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, UUID entityId,
            String before, String after) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, ENROLLMENT,
                entityId.toString(), OUTCOME_SUCCESS, before, after, null, null);
    }
}
