package com.confia.catalog.infrastructure;

import static confia.generated.jooq.tables.CatalogPaymentConcept.CATALOG_PAYMENT_CONCEPT;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptLabel;
import com.confia.catalog.application.directory.PaymentConceptRef;
import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link PaymentConceptDirectory}: one query that reads the identifier and the state and locks the
 * row in share mode. It never opens a transaction and never sets the institution of the session; row-level security does
 * the second filter on every statement.
 */
public final class JooqPaymentConceptDirectory implements PaymentConceptDirectory {

    private final DSLContext dsl;

    public JooqPaymentConceptDirectory(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<PaymentConceptRef> lockShare(InstitutionId institutionId, UUID paymentConceptId) {
        return dsl.select(CATALOG_PAYMENT_CONCEPT.ID, CATALOG_PAYMENT_CONCEPT.IS_ACTIVE)
                .from(CATALOG_PAYMENT_CONCEPT)
                .where(CATALOG_PAYMENT_CONCEPT.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PAYMENT_CONCEPT.ID.eq(paymentConceptId))
                .forShare()
                .fetchOptional(row -> new PaymentConceptRef(row.get(CATALOG_PAYMENT_CONCEPT.ID),
                        row.get(CATALOG_PAYMENT_CONCEPT.IS_ACTIVE)));
    }

    @Override
    public Map<UUID, PaymentConceptLabel> labels(InstitutionId institutionId, Collection<UUID> paymentConceptIds) {
        Map<UUID, PaymentConceptLabel> found = new HashMap<>();
        if (paymentConceptIds.isEmpty()) {
            return found;
        }
        dsl.select(CATALOG_PAYMENT_CONCEPT.ID, CATALOG_PAYMENT_CONCEPT.CODE, CATALOG_PAYMENT_CONCEPT.NAME)
                .from(CATALOG_PAYMENT_CONCEPT)
                .where(CATALOG_PAYMENT_CONCEPT.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PAYMENT_CONCEPT.ID.in(paymentConceptIds))
                .forEach(row -> found.put(row.get(CATALOG_PAYMENT_CONCEPT.ID),
                        new PaymentConceptLabel(row.get(CATALOG_PAYMENT_CONCEPT.ID),
                                row.get(CATALOG_PAYMENT_CONCEPT.CODE), row.get(CATALOG_PAYMENT_CONCEPT.NAME))));
        return found;
    }
}
