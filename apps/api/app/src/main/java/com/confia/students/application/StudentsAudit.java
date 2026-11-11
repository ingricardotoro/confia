package com.confia.students.application;

import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.students.domain.Student;
import java.util.UUID;

/**
 * The entries of the registry: {@code students.student.created} with the state after, and {@code
 * students.student.updated} with the state before and after, built with {@link AuditJson} and never by
 * concatenation. The national identity document is never part of them, nor its ciphertext nor its blind
 * index: only whether the student has one and its last four characters.
 */
final class StudentsAudit {

    static final String STUDENT = "students.student";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private StudentsAudit() {
    }

    /** The identifier of the request for the entry; a blank one gets a fresh identifier. */
    static UUID requestId(String requestId) {
        return requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
    }

    static AuditEntry created(AuthenticatedActor actor, UUID requestId, Student after) {
        return entry(actor, requestId, STUDENT + ".created", after.id(), null, json(after));
    }

    static AuditEntry updated(AuthenticatedActor actor, UUID requestId, Student before, Student after) {
        return entry(actor, requestId, STUDENT + ".updated", after.id(), json(before), json(after));
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, UUID entityId,
            String before, String after) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, STUDENT,
                entityId.toString(), OUTCOME_SUCCESS, before, after, null, null);
    }

    static String json(Student student) {
        AuditJson.Builder json = AuditJson.object().text("studentCode", student.studentCode())
                .text("firstName", student.firstName()).text("lastName", student.lastName())
                .text("birthDate", student.birthDate().toString()).text("sex", student.sex().name())
                .text("status", student.status().name()).bool("hasNationalId", student.hasNationalId());
        if (student.hasNationalId()) {
            json.text("nationalIdLast4", student.nationalIdLast4());
        }
        return json.toJson();
    }
}
