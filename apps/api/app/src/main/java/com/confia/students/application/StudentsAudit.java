package com.confia.students.application;

import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.students.domain.Student;
import com.confia.students.domain.NationalIdChange;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The entries of the registry: {@code students.student.created} with the state after, and {@code
 * students.student.updated} with the state before and after, built with {@link AuditJson} and never by
 * concatenation. The audit trail is kept seven years and never erased, so it holds no personal data of the
 * minor: no name, no birth date, no sex and no character of the national identity document, nor its
 * ciphertext nor its blind index. It holds the student code, the status, whether there is a document and,
 * for an update, the names (never the values) of the fields that changed.
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
        return entry(actor, requestId, STUDENT + ".created", after.id(), null, json(after, null));
    }

    static AuditEntry updated(AuthenticatedActor actor, UUID requestId, Student before, Student after,
            NationalIdChange document) {
        return entry(actor, requestId, STUDENT + ".updated", after.id(), json(before, null),
                json(after, changedFields(before, after, document)));
    }

    /** The names of the members that differ; the document counts when it is replaced or removed. */
    static List<String> changedFields(Student before, Student after, NationalIdChange document) {
        List<String> changed = new ArrayList<>();
        if (!before.firstName().equals(after.firstName())) {
            changed.add("firstName");
        }
        if (!before.lastName().equals(after.lastName())) {
            changed.add("lastName");
        }
        if (!before.birthDate().equals(after.birthDate())) {
            changed.add("birthDate");
        }
        if (before.sex() != after.sex()) {
            changed.add("sex");
        }
        if (before.status() != after.status()) {
            changed.add("status");
        }
        if (!(document instanceof NationalIdChange.Keep)) {
            changed.add("nationalId");
        }
        return changed;
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, UUID entityId,
            String before, String after) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, STUDENT,
                entityId.toString(), OUTCOME_SUCCESS, before, after, null, null);
    }

    /** Code, status and whether there is a document; the names of the changed fields, comma separated. */
    static String json(Student student, List<String> changedFields) {
        AuditJson.Builder json = AuditJson.object().text("studentCode", student.studentCode())
                .text("status", student.status().name()).bool("hasNationalId", student.hasNationalId());
        if (changedFields != null) {
            json.text("changedFields", String.join(",", changedFields));
        }
        return json.toJson();
    }
}
