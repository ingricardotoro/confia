package com.confia.catalog.application;

import com.confia.catalog.domain.PaymentConcept;
import com.confia.catalog.domain.PriceList;
import com.confia.catalog.domain.PriceListItem;
import com.confia.catalog.domain.TaxRate;
import com.confia.catalog.domain.TaxRatePeriod;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The entries of the catalog: {@code catalog.payment_concept.created|updated}, {@code
 * catalog.price_list.created|updated} (with the state before and after on an update) and {@code
 * catalog.price_list_item.created}, which also records the previous validity that the new price closed, and {@code
 * catalog.tax_rate.created|updated} and {@code catalog.tax_rate_period.created} (which records the previous period it
 * closed, as the price does). The entries of a concept carry the identifier of its tax rate. They are built with {@link
 * AuditJson} and never by concatenation, and carry only the fields of the concept, the list, the price and the tax rate
 * (codes, names, identifiers, amounts, percentages, dates): no personal datum exists in this module.
 */
final class CatalogAudit {

    static final String PAYMENT_CONCEPT = "catalog.payment_concept";
    static final String PRICE_LIST = "catalog.price_list";
    static final String PRICE_LIST_ITEM = "catalog.price_list_item";
    static final String TAX_RATE = "catalog.tax_rate";
    static final String TAX_RATE_PERIOD = "catalog.tax_rate_period";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private CatalogAudit() {
    }

    /** The identifier of the request for the entry; a blank one gets a fresh identifier. */
    static UUID requestId(String requestId) {
        return requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
    }

    static AuditEntry created(AuthenticatedActor actor, UUID requestId, UUID entityId, String after) {
        return entry(actor, requestId, PAYMENT_CONCEPT + ".created", PAYMENT_CONCEPT, entityId, null, after);
    }

    static AuditEntry updated(AuthenticatedActor actor, UUID requestId, UUID entityId, String before,
            String after) {
        return entry(actor, requestId, PAYMENT_CONCEPT + ".updated", PAYMENT_CONCEPT, entityId, before, after);
    }

    static AuditEntry priceListCreated(AuthenticatedActor actor, UUID requestId, UUID entityId, String after) {
        return entry(actor, requestId, PRICE_LIST + ".created", PRICE_LIST, entityId, null, after);
    }

    static AuditEntry priceListUpdated(AuthenticatedActor actor, UUID requestId, UUID entityId, String before,
            String after) {
        return entry(actor, requestId, PRICE_LIST + ".updated", PRICE_LIST, entityId, before, after);
    }

    static AuditEntry priceListItemCreated(AuthenticatedActor actor, UUID requestId, UUID entityId, String after) {
        return entry(actor, requestId, PRICE_LIST_ITEM + ".created", PRICE_LIST_ITEM, entityId, null, after);
    }

    static AuditEntry taxRateCreated(AuthenticatedActor actor, UUID requestId, UUID entityId, String after) {
        return entry(actor, requestId, TAX_RATE + ".created", TAX_RATE, entityId, null, after);
    }

    static AuditEntry taxRateUpdated(AuthenticatedActor actor, UUID requestId, UUID entityId, String before,
            String after) {
        return entry(actor, requestId, TAX_RATE + ".updated", TAX_RATE, entityId, before, after);
    }

    static AuditEntry taxRatePeriodCreated(AuthenticatedActor actor, UUID requestId, UUID entityId, String after) {
        return entry(actor, requestId, TAX_RATE_PERIOD + ".created", TAX_RATE_PERIOD, entityId, null, after);
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, String entityType,
            UUID entityId, String before, String after) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, entityType,
                entityId.toString(), OUTCOME_SUCCESS, before, after, null, null);
    }

    static String json(PaymentConcept concept) {
        AuditJson.Builder json = AuditJson.object().text("code", concept.code()).text("name", concept.name())
                .text("category", concept.category().name()).bool("isTaxable", concept.taxable())
                .bool("isRecurring", concept.recurring()).bool("isActive", concept.active());
        if (concept.taxRateId() != null) {
            json.text("taxRateId", concept.taxRateId().toString());
        }
        return json.toJson();
    }

    static String json(TaxRate rate) {
        return AuditJson.object().text("code", rate.code()).text("name", rate.name()).bool("isActive", rate.active())
                .number("version", rate.version()).toJson();
    }

    /**
     * The new period, and the previous one it closed: its identifier and its last day, which it did not have before.
     *
     * @param closedPreviousId the open period the new one replaced, or {@code null} when there was none
     * @param closedPreviousLastDay the day the previous period was closed on
     */
    static String json(TaxRatePeriod period, UUID closedPreviousId, LocalDate closedPreviousLastDay) {
        AuditJson.Builder json = AuditJson.object().text("taxRateId", period.taxRateId().toString())
                .text("ratePercent", period.ratePercent().toPlainString())
                .text("validFrom", period.validFrom().toString());
        if (period.validTo() != null) {
            json.text("validTo", period.validTo().toString());
        }
        if (closedPreviousId != null) {
            json.text("closedPreviousPeriodId", closedPreviousId.toString())
                    .text("closedPreviousValidTo", closedPreviousLastDay.toString());
        }
        return json.toJson();
    }

    static String json(PriceList list) {
        AuditJson.Builder json = AuditJson.object().text("name", list.name())
                .text("academicYearId", list.academicYearId().toString()).text("scope", list.scope().name());
        if (list.scopeRefId() != null) {
            json.text("scopeRefId", list.scopeRefId().toString());
        }
        return json.text("status", list.status().name()).number("version", list.version()).toJson();
    }

    /**
     * The new price, and the previous validity it closed: its identifier and its last day, which it did not have before.
     *
     * @param closedPreviousId the open validity the new price replaced, or {@code null} when there was none
     * @param closedPreviousLastDay the day the previous validity was closed on
     */
    static String json(PriceListItem item, UUID closedPreviousId, LocalDate closedPreviousLastDay) {
        AuditJson.Builder json = AuditJson.object().text("priceListId", item.priceListId().toString())
                .text("paymentConceptId", item.paymentConceptId().toString())
                .text("amount", item.price().toPlainString()).text("currency", item.price().currency().name())
                .text("validFrom", item.validFrom().toString());
        if (item.validTo() != null) {
            json.text("validTo", item.validTo().toString());
        }
        if (closedPreviousId != null) {
            json.text("closedPreviousItemId", closedPreviousId.toString())
                    .text("closedPreviousValidTo", closedPreviousLastDay.toString());
        }
        return json.toJson();
    }
}
