package com.confia.catalog.infrastructure;

import static confia.generated.jooq.tables.CatalogPriceList.CATALOG_PRICE_LIST;

import com.confia.catalog.application.Page;
import com.confia.catalog.application.PageRequest;
import com.confia.catalog.application.PriceListFilter;
import com.confia.catalog.application.PriceListRepository;
import com.confia.catalog.domain.PriceList;
import com.confia.catalog.domain.PriceListScope;
import com.confia.catalog.domain.PriceListStatus;
import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.catalog.domain.StaleVersionException;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.CatalogPriceListRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;

/** The jOOQ adapter of {@link PriceListRepository} over {@code catalog_price_list}. */
public final class JooqPriceListRepository implements PriceListRepository {

    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "name", CATALOG_PRICE_LIST.NAME,
            "createdAt", CATALOG_PRICE_LIST.CREATED_AT);

    private final DSLContext dsl;

    public JooqPriceListRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<PriceList> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(CATALOG_PRICE_LIST)
                .where(CATALOG_PRICE_LIST.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PRICE_LIST.ID.eq(id))
                .fetchOptional().map(JooqPriceListRepository::toDomain);
    }

    @Override
    public Optional<PriceList> lockById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(CATALOG_PRICE_LIST)
                .where(CATALOG_PRICE_LIST.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PRICE_LIST.ID.eq(id))
                .forUpdate()
                .fetchOptional().map(JooqPriceListRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, PriceList list) {
        CatalogWrites.translating(() -> dsl.insertInto(CATALOG_PRICE_LIST)
                .set(CATALOG_PRICE_LIST.INSTITUTION_ID, institutionId.value())
                .set(CATALOG_PRICE_LIST.ID, list.id())
                .set(CATALOG_PRICE_LIST.ACADEMIC_YEAR_ID, list.academicYearId())
                .set(CATALOG_PRICE_LIST.NAME, list.name())
                .set(CATALOG_PRICE_LIST.SCOPE, list.scope().name())
                .set(CATALOG_PRICE_LIST.SCOPE_REF_ID, list.scopeRefId())
                .set(CATALOG_PRICE_LIST.STATUS, list.status().name())
                .set(CATALOG_PRICE_LIST.VERSION, list.version())
                .set(CATALOG_PRICE_LIST.CREATED_AT, CatalogWrites.toDatabase(list.createdAt()))
                .set(CATALOG_PRICE_LIST.UPDATED_AT, CatalogWrites.toDatabase(list.updatedAt()))
                .execute());
    }

    @Override
    public void update(InstitutionId institutionId, PriceList list, long expectedVersion) {
        CatalogWrites.translating(() -> {
            // The year, the scope and the reference are never written: they are immutable once the list exists.
            int rows = dsl.update(CATALOG_PRICE_LIST)
                    .set(CATALOG_PRICE_LIST.NAME, list.name())
                    .set(CATALOG_PRICE_LIST.STATUS, list.status().name())
                    .set(CATALOG_PRICE_LIST.VERSION, list.version())
                    .set(CATALOG_PRICE_LIST.UPDATED_AT, CatalogWrites.toDatabase(list.updatedAt()))
                    .where(CATALOG_PRICE_LIST.INSTITUTION_ID.eq(institutionId.value()))
                    .and(CATALOG_PRICE_LIST.ID.eq(list.id()))
                    .and(CATALOG_PRICE_LIST.VERSION.eq(expectedVersion))
                    .execute();
            if (rows == 0) {
                boolean exists = dsl.fetchExists(CATALOG_PRICE_LIST,
                        CATALOG_PRICE_LIST.INSTITUTION_ID.eq(institutionId.value())
                                .and(CATALOG_PRICE_LIST.ID.eq(list.id())));
                throw exists ? new StaleVersionException() : new ResourceNotFoundException();
            }
        });
    }

    @Override
    public Page<PriceList> list(InstitutionId institutionId, PageRequest request, PriceListFilter filter) {
        Condition scope = CATALOG_PRICE_LIST.INSTITUTION_ID.eq(institutionId.value());
        if (filter.academicYearId() != null) {
            scope = scope.and(CATALOG_PRICE_LIST.ACADEMIC_YEAR_ID.eq(filter.academicYearId()));
        }
        if (filter.scope() != null) {
            scope = scope.and(CATALOG_PRICE_LIST.SCOPE.eq(filter.scope().name()));
        }
        if (filter.status() != null) {
            scope = scope.and(CATALOG_PRICE_LIST.STATUS.eq(filter.status().name()));
        }
        long total = dsl.fetchCount(CATALOG_PRICE_LIST, scope);
        List<PriceList> rows = dsl.selectFrom(CATALOG_PRICE_LIST).where(scope)
                .orderBy(order(request), CATALOG_PRICE_LIST.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqPriceListRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(PageRequest request) {
        // Names compare without regard to capitals, whatever the collation of the database.
        Field<?> field = request.sortField().equals("name") ? DSL.lower(CATALOG_PRICE_LIST.NAME)
                : SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static PriceList toDomain(CatalogPriceListRecord row) {
        return new PriceList(row.getId(), row.getAcademicYearId(), row.getName(),
                PriceListScope.parse(row.getScope()), row.getScopeRefId(), PriceListStatus.parse(row.getStatus()),
                row.getVersion(), CatalogWrites.fromDatabase(row.getCreatedAt()),
                CatalogWrites.fromDatabase(row.getUpdatedAt()));
    }
}
