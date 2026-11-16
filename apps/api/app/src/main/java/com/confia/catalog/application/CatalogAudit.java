package com.confia.catalog.application;

import com.confia.catalog.domain.PaymentConcept;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.util.UUID;

/**
 * The entries of the catalog: {@code catalog.payment_concept.created} with the state after, and {@code
 * catalog.payment_concept.updated} with the state before and after, built with {@link AuditJson} and never by
 * concatenation. They carry only the fields of the concept (code, name, category, flags): no personal datum exists in
 * this module.
 */
final class CatalogAudit {

    static final String PAYMENT_CONCEPT = "catalog.payment_concept";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private CatalogAudit() {
    }

    /** The identifier of the request for the entry; a blank one gets a fresh identifier. */
    static UUID requestId(String requestId) {
        return requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
    }

    static AuditEntry created(AuthenticatedActor actor, UUID requestId, UUID entityId, String after) {
        return entry(actor, requestId, PAYMENT_CONCEPT + ".created", entityId, null, after);
    }

    static AuditEntry updated(AuthenticatedActor actor, UUID requestId, UUID entityId, String before,
            String after) {
        return entry(actor, requestId, PAYMENT_CONCEPT + ".updated", entityId, before, after);
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, UUID entityId,
            String before, String after) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, PAYMENT_CONCEPT,
                entityId.toString(), OUTCOME_SUCCESS, before, after, null, null);
    }

    static String json(PaymentConcept concept) {
        return AuditJson.object().text("code", concept.code()).text("name", concept.name())
                .text("category", concept.category().name()).bool("isTaxable", concept.taxable())
                .bool("isRecurring", concept.recurring()).bool("isActive", concept.active()).toJson();
    }
}
