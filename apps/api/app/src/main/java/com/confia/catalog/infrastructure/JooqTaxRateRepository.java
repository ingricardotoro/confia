package com.confia.catalog.infrastructure;

import static confia.generated.jooq.tables.CatalogTaxRate.CATALOG_TAX_RATE;

import com.confia.catalog.application.Page;
import com.confia.catalog.application.PageRequest;
import com.confia.catalog.application.TaxRateFilter;
import com.confia.catalog.application.TaxRateRepository;
import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.catalog.domain.StaleVersionException;
import com.confia.catalog.domain.TaxRate;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.CatalogTaxRateRecord;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;

/** The jOOQ adapter of {@link TaxRateRepository} over {@code catalog_tax_rate}. */
public final class JooqTaxRateRepository implements TaxRateRepository {

    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "code", CATALOG_TAX_RATE.CODE,
            "name", CATALOG_TAX_RATE.NAME);

    private final DSLContext dsl;

    public JooqTaxRateRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<TaxRate> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(CATALOG_TAX_RATE)
                .where(CATALOG_TAX_RATE.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_TAX_RATE.ID.eq(id))
                .fetchOptional().map(JooqTaxRateRepository::toDomain);
    }

    @Override
    public Map<UUID, TaxRate> findByIds(InstitutionId institutionId, Collection<UUID> ids) {
        Map<UUID, TaxRate> found = new HashMap<>();
        dsl.selectFrom(CATALOG_TAX_RATE)
                .where(CATALOG_TAX_RATE.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_TAX_RATE.ID.in(ids))
                .fetch(JooqTaxRateRepository::toDomain)
                .forEach(rate -> found.put(rate.id(), rate));
        return found;
    }

    @Override
    public Optional<TaxRate> lockById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(CATALOG_TAX_RATE)
                .where(CATALOG_TAX_RATE.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_TAX_RATE.ID.eq(id))
                .forUpdate()
                .fetchOptional().map(JooqTaxRateRepository::toDomain);
    }

    @Override
    public Optional<TaxRate> lockShareById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(CATALOG_TAX_RATE)
                .where(CATALOG_TAX_RATE.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_TAX_RATE.ID.eq(id))
                .forShare()
                .fetchOptional().map(JooqTaxRateRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, TaxRate rate) {
        CatalogWrites.translating(() -> dsl.insertInto(CATALOG_TAX_RATE)
                .set(CATALOG_TAX_RATE.INSTITUTION_ID, institutionId.value())
                .set(CATALOG_TAX_RATE.ID, rate.id())
                .set(CATALOG_TAX_RATE.CODE, rate.code())
                .set(CATALOG_TAX_RATE.NAME, rate.name())
                .set(CATALOG_TAX_RATE.IS_ACTIVE, rate.active())
                .set(CATALOG_TAX_RATE.VERSION, rate.version())
                .set(CATALOG_TAX_RATE.CREATED_AT, CatalogWrites.toDatabase(rate.createdAt()))
                .set(CATALOG_TAX_RATE.UPDATED_AT, CatalogWrites.toDatabase(rate.updatedAt()))
                .execute());
    }

    @Override
    public void update(InstitutionId institutionId, TaxRate rate, long expectedVersion) {
        CatalogWrites.translating(() -> {
            // The code is never written: it is immutable once the rate exists.
            int rows = dsl.update(CATALOG_TAX_RATE)
                    .set(CATALOG_TAX_RATE.NAME, rate.name())
                    .set(CATALOG_TAX_RATE.IS_ACTIVE, rate.active())
                    .set(CATALOG_TAX_RATE.VERSION, rate.version())
                    .set(CATALOG_TAX_RATE.UPDATED_AT, CatalogWrites.toDatabase(rate.updatedAt()))
                    .where(CATALOG_TAX_RATE.INSTITUTION_ID.eq(institutionId.value()))
                    .and(CATALOG_TAX_RATE.ID.eq(rate.id()))
                    .and(CATALOG_TAX_RATE.VERSION.eq(expectedVersion))
                    .execute();
            if (rows == 0) {
                boolean exists = dsl.fetchExists(CATALOG_TAX_RATE,
                        CATALOG_TAX_RATE.INSTITUTION_ID.eq(institutionId.value())
                                .and(CATALOG_TAX_RATE.ID.eq(rate.id())));
                throw exists ? new StaleVersionException() : new ResourceNotFoundException();
            }
        });
    }

    @Override
    public Page<TaxRate> list(InstitutionId institutionId, PageRequest request, TaxRateFilter filter) {
        Condition scope = CATALOG_TAX_RATE.INSTITUTION_ID.eq(institutionId.value());
        if (filter.active() != null) {
            scope = scope.and(CATALOG_TAX_RATE.IS_ACTIVE.eq(filter.active()));
        }
        long total = dsl.fetchCount(CATALOG_TAX_RATE, scope);
        List<TaxRate> rows = dsl.selectFrom(CATALOG_TAX_RATE).where(scope)
                .orderBy(order(request), CATALOG_TAX_RATE.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqTaxRateRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(PageRequest request) {
        // Codes compare without regard to capitals, as their uniqueness does.
        Field<?> field = request.sortField().equals("code") ? DSL.lower(CATALOG_TAX_RATE.CODE)
                : SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static TaxRate toDomain(CatalogTaxRateRecord row) {
        return new TaxRate(row.getId(), row.getCode(), row.getName(), row.getIsActive(), row.getVersion(),
                CatalogWrites.fromDatabase(row.getCreatedAt()), CatalogWrites.fromDatabase(row.getUpdatedAt()));
    }
}
