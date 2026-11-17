package com.confia.catalog.infrastructure;

import static confia.generated.jooq.tables.CatalogPaymentConcept.CATALOG_PAYMENT_CONCEPT;

import com.confia.catalog.application.Page;
import com.confia.catalog.application.PageRequest;
import com.confia.catalog.application.PaymentConceptFilter;
import com.confia.catalog.application.PaymentConceptRepository;
import com.confia.catalog.domain.PaymentCategory;
import com.confia.catalog.domain.PaymentConcept;
import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.catalog.domain.StaleVersionException;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.CatalogPaymentConceptRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;

/** The jOOQ adapter of {@link PaymentConceptRepository} over {@code catalog_payment_concept}. */
public final class JooqPaymentConceptRepository implements PaymentConceptRepository {

    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "code", CATALOG_PAYMENT_CONCEPT.CODE,
            "name", CATALOG_PAYMENT_CONCEPT.NAME);

    private final DSLContext dsl;

    public JooqPaymentConceptRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<PaymentConcept> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(CATALOG_PAYMENT_CONCEPT)
                .where(CATALOG_PAYMENT_CONCEPT.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PAYMENT_CONCEPT.ID.eq(id))
                .fetchOptional().map(JooqPaymentConceptRepository::toDomain);
    }

    @Override
    public Optional<PaymentConcept> lockShareById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(CATALOG_PAYMENT_CONCEPT)
                .where(CATALOG_PAYMENT_CONCEPT.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PAYMENT_CONCEPT.ID.eq(id))
                .forShare()
                .fetchOptional().map(JooqPaymentConceptRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, PaymentConcept concept) {
        CatalogWrites.translating(() -> dsl.insertInto(CATALOG_PAYMENT_CONCEPT)
                .set(CATALOG_PAYMENT_CONCEPT.INSTITUTION_ID, institutionId.value())
                .set(CATALOG_PAYMENT_CONCEPT.ID, concept.id())
                .set(CATALOG_PAYMENT_CONCEPT.CODE, concept.code())
                .set(CATALOG_PAYMENT_CONCEPT.NAME, concept.name())
                .set(CATALOG_PAYMENT_CONCEPT.CATEGORY, concept.category().name())
                .set(CATALOG_PAYMENT_CONCEPT.IS_TAXABLE, concept.taxable())
                .set(CATALOG_PAYMENT_CONCEPT.IS_RECURRING, concept.recurring())
                .set(CATALOG_PAYMENT_CONCEPT.IS_ACTIVE, concept.active())
                .set(CATALOG_PAYMENT_CONCEPT.TAX_RATE_ID, concept.taxRateId())
                .set(CATALOG_PAYMENT_CONCEPT.VERSION, concept.version())
                .set(CATALOG_PAYMENT_CONCEPT.CREATED_AT, CatalogWrites.toDatabase(concept.createdAt()))
                .set(CATALOG_PAYMENT_CONCEPT.UPDATED_AT, CatalogWrites.toDatabase(concept.updatedAt()))
                .execute());
    }

    @Override
    public void update(InstitutionId institutionId, PaymentConcept concept, long expectedVersion) {
        CatalogWrites.translating(() -> {
            // The code is never written: it is immutable once the concept exists.
            int rows = dsl.update(CATALOG_PAYMENT_CONCEPT)
                    .set(CATALOG_PAYMENT_CONCEPT.NAME, concept.name())
                    .set(CATALOG_PAYMENT_CONCEPT.CATEGORY, concept.category().name())
                    .set(CATALOG_PAYMENT_CONCEPT.IS_TAXABLE, concept.taxable())
                    .set(CATALOG_PAYMENT_CONCEPT.IS_RECURRING, concept.recurring())
                    .set(CATALOG_PAYMENT_CONCEPT.IS_ACTIVE, concept.active())
                    .set(CATALOG_PAYMENT_CONCEPT.TAX_RATE_ID, concept.taxRateId())
                    .set(CATALOG_PAYMENT_CONCEPT.VERSION, concept.version())
                    .set(CATALOG_PAYMENT_CONCEPT.UPDATED_AT, CatalogWrites.toDatabase(concept.updatedAt()))
                    .where(CATALOG_PAYMENT_CONCEPT.INSTITUTION_ID.eq(institutionId.value()))
                    .and(CATALOG_PAYMENT_CONCEPT.ID.eq(concept.id()))
                    .and(CATALOG_PAYMENT_CONCEPT.VERSION.eq(expectedVersion))
                    .execute();
            if (rows == 0) {
                // Nothing matched: either the row is not in the institution, or somebody else edited it after the
                // editor read it (the version moved on).
                boolean exists = dsl.fetchExists(CATALOG_PAYMENT_CONCEPT,
                        CATALOG_PAYMENT_CONCEPT.INSTITUTION_ID.eq(institutionId.value())
                                .and(CATALOG_PAYMENT_CONCEPT.ID.eq(concept.id())));
                throw exists ? new StaleVersionException() : new ResourceNotFoundException();
            }
        });
    }

    @Override
    public Page<PaymentConcept> list(InstitutionId institutionId, PageRequest request,
            PaymentConceptFilter filter) {
        Condition scope = CATALOG_PAYMENT_CONCEPT.INSTITUTION_ID.eq(institutionId.value());
        if (filter.category() != null) {
            scope = scope.and(CATALOG_PAYMENT_CONCEPT.CATEGORY.eq(filter.category().name()));
        }
        if (filter.active() != null) {
            scope = scope.and(CATALOG_PAYMENT_CONCEPT.IS_ACTIVE.eq(filter.active()));
        }
        long total = dsl.fetchCount(CATALOG_PAYMENT_CONCEPT, scope);
        List<PaymentConcept> rows = dsl.selectFrom(CATALOG_PAYMENT_CONCEPT).where(scope)
                .orderBy(order(request), CATALOG_PAYMENT_CONCEPT.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqPaymentConceptRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(PageRequest request) {
        // Codes compare without regard to capitals, as their uniqueness does.
        Field<?> field = request.sortField().equals("code") ? DSL.lower(CATALOG_PAYMENT_CONCEPT.CODE)
                : SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static PaymentConcept toDomain(CatalogPaymentConceptRecord row) {
        return new PaymentConcept(row.getId(), row.getCode(), row.getName(),
                PaymentCategory.parse(row.getCategory()), row.getIsTaxable(), row.getIsRecurring(),
                row.getIsActive(), row.getTaxRateId(), row.getVersion(), CatalogWrites.fromDatabase(row.getCreatedAt()),
                CatalogWrites.fromDatabase(row.getUpdatedAt()));
    }
}
