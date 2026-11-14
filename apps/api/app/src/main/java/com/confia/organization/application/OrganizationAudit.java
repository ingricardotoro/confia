package com.confia.organization.application;

import com.confia.organization.domain.AcademicYear;
import com.confia.organization.domain.Grade;
import com.confia.organization.domain.Modality;
import com.confia.organization.domain.Section;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.util.UUID;

/**
 * The entries of the academic structure: {@code organization.<entity>.created} with the state after,
 * and {@code organization.<entity>.updated} with the state before and after, built with {@link
 * AuditJson} and never by concatenation. They carry only the fields of the structure (names, codes,
 * dates, flags): no personal datum exists in this module.
 */
final class OrganizationAudit {

    static final String ACADEMIC_YEAR = "organization.academic_year";
    static final String MODALITY = "organization.modality";
    static final String GRADE = "organization.grade";
    static final String SECTION = "organization.section";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private OrganizationAudit() {
    }

    /** The identifier of the request for the entry; a blank one gets a fresh identifier. */
    static UUID requestId(String requestId) {
        return requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
    }

    static AuditEntry created(AuthenticatedActor actor, UUID requestId, String entityType,
            UUID entityId, String after) {
        return entry(actor, requestId, entityType + ".created", entityType, entityId, null, after);
    }

    static AuditEntry updated(AuthenticatedActor actor, UUID requestId, String entityType,
            UUID entityId, String before, String after) {
        return entry(actor, requestId, entityType + ".updated", entityType, entityId, before, after);
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action,
            String entityType, UUID entityId, String before, String after) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, entityType,
                entityId.toString(), OUTCOME_SUCCESS, before, after, null, null);
    }

    static String json(AcademicYear year) {
        return AuditJson.object().text("name", year.name()).text("startDate", year.startDate().toString())
                .text("endDate", year.endDate().toString()).text("status", year.status().name())
                .bool("isActive", year.active()).toJson();
    }

    static String json(Modality modality) {
        return AuditJson.object().text("code", modality.code()).text("name", modality.name())
                .bool("isActive", modality.active()).toJson();
    }

    static String json(Grade grade) {
        return AuditJson.object().text("modalityId", grade.modalityId().toString())
                .text("name", grade.name()).number("ordinal", grade.ordinal())
                .bool("isActive", grade.active()).toJson();
    }

    static String json(Section section) {
        AuditJson.Builder json = AuditJson.object()
                .text("academicYearId", section.academicYearId().toString())
                .text("gradeId", section.gradeId().toString()).text("name", section.name());
        if (section.capacity() != null) {
            json.number("capacity", section.capacity());
        }
        return json.bool("isActive", section.active()).toJson();
    }
}
