package com.confia.guardians.application;

import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianLink;
import com.confia.guardians.domain.ProtectedValueChange;
import com.confia.guardians.domain.NationalId;
import com.confia.guardians.domain.ResponsibilitySplit;
import com.confia.guardians.domain.Rtn;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The entries of the guardians: {@code guardians.guardian.created}, {@code guardians.guardian.updated} and {@code
 * guardians.links.replaced}, built with {@link AuditJson} and never by concatenation. The audit trail is kept seven
 * years and never erased, so it holds no personal data: no name, no document, no RTN, no e-mail, no phone and no
 * address. It holds codes, statuses, whether a member is present, the percentages, and the names (never the
 * values) of the fields that changed.
 */
final class GuardiansAudit {

    static final String GUARDIAN = "guardians.guardian";
    static final String LINKS = "guardians.links";

    static final String CREATE_ACTION = GUARDIAN + ".created";
    static final String UPDATE_ACTION = GUARDIAN + ".updated";
    /** The reason code of a refused attempt: a fixed word, never the document that collided. */
    static final String REASON_DUPLICATE_NATIONAL_ID = "duplicate-national-id";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_DENIED = "denied";

    private GuardiansAudit() {
    }

    /** The identifier of the request for the entry; a blank one gets a fresh identifier. */
    static UUID requestId(String requestId) {
        return requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
    }

    /**
     * A refused write: the outcome is {@code denied}, the one refusal value shared_audit_log accepts (V2 allows only
     * success, denied and error). The action attempted, the guardian it aimed at and a reason code; no values at all.
     */
    static AuditEntry rejected(AuthenticatedActor actor, UUID requestId, String action, UUID guardianId,
            String reason) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, GUARDIAN, guardianId.toString(),
                OUTCOME_DENIED, null, null, reason, null);
    }

    static AuditEntry created(AuthenticatedActor actor, UUID requestId, Guardian after) {
        return entry(actor, requestId, CREATE_ACTION, GUARDIAN, after.id(), null, json(after, null));
    }

    static AuditEntry updated(AuthenticatedActor actor, UUID requestId, Guardian before, Guardian after,
            ProtectedValueChange<NationalId> document, ProtectedValueChange<Rtn> rtn) {
        return entry(actor, requestId, UPDATE_ACTION, GUARDIAN, after.id(), json(before, null),
                json(after, changedFields(before, after, document, rtn)));
    }

    /** The names of the members that differ; the document and the RTN count when replaced or removed. */
    static List<String> changedFields(Guardian before, Guardian after, ProtectedValueChange<NationalId> document,
            ProtectedValueChange<Rtn> rtn) {
        List<String> changed = new ArrayList<>();
        if (!before.firstName().equals(after.firstName())) {
            changed.add("firstName");
        }
        if (!before.lastName().equals(after.lastName())) {
            changed.add("lastName");
        }
        if (!Objects.equals(before.email(), after.email())) {
            changed.add("email");
        }
        if (!Objects.equals(before.phone(), after.phone())) {
            changed.add("phone");
        }
        if (!Objects.equals(before.address(), after.address())) {
            changed.add("address");
        }
        if (before.preferredChannel() != after.preferredChannel()) {
            changed.add("preferredChannel");
        }
        if (before.status() != after.status()) {
            changed.add("status");
        }
        if (!(document instanceof ProtectedValueChange.Keep<NationalId>)) {
            changed.add("nationalId");
        }
        if (!(rtn instanceof ProtectedValueChange.Keep<Rtn>)) {
            changed.add("rtn");
        }
        return changed;
    }

    /**
     * The replacement of the current links of a student: the code of the student and, before and after, the
     * number of links, their exact total and each link as {@code guardianCode:RELATIONSHIP:percent} followed by
     * {@code :primary} and {@code :billing} when they apply. {@code codes} names the guardian of each link.
     */
    static AuditEntry linksReplaced(AuthenticatedActor actor, UUID requestId, UUID studentId, String studentCode,
            List<GuardianLink> before, List<GuardianLink> after, Map<UUID, String> codes) {
        return entry(actor, requestId, LINKS + ".replaced", LINKS, studentId, linksJson(studentCode, before, codes),
                linksJson(studentCode, after, codes));
    }

    private static String linksJson(String studentCode, List<GuardianLink> links, Map<UUID, String> codes) {
        BigDecimal total = ResponsibilitySplit.total(links.stream().map(GuardianLink::percent).toList());
        List<String> described = new ArrayList<>();
        for (GuardianLink link : links) {
            StringBuilder text = new StringBuilder(codes.get(link.guardianId())).append(':')
                    .append(link.relationship().name()).append(':').append(link.percent().toPlainString());
            if (link.primaryPayer()) {
                text.append(":primary");
            }
            if (link.billingRecipient()) {
                text.append(":billing");
            }
            described.add(text.toString());
        }
        return AuditJson.object().text("studentCode", studentCode).number("linkCount", links.size())
                .text("totalPercent", total.toPlainString()).text("links", String.join(",", described)).toJson();
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, String entityType,
            UUID entityId, String before, String after) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, entityType,
                entityId.toString(), OUTCOME_SUCCESS, before, after, null, null);
    }

    /** Code, status, the channel, whether each optional member is present, and the names of the changed fields. */
    static String json(Guardian guardian, List<String> changedFields) {
        AuditJson.Builder json = AuditJson.object().text("guardianCode", guardian.guardianCode())
                .text("status", guardian.status().name())
                .text("preferredChannel", guardian.preferredChannel().name())
                .bool("hasNationalId", guardian.hasNationalId()).bool("hasRtn", guardian.hasRtn())
                .bool("hasEmail", guardian.email() != null).bool("hasPhone", guardian.phone() != null)
                .bool("hasAddress", guardian.address() != null);
        if (changedFields != null) {
            json.text("changedFields", String.join(",", changedFields));
        }
        return json.toJson();
    }
}
