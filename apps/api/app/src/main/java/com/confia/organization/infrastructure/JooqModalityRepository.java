package com.confia.organization.infrastructure;

import static confia.generated.jooq.tables.OrganizationModality.ORGANIZATION_MODALITY;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.ModalityRepository;
import com.confia.organization.application.Page;
import com.confia.organization.application.PageRequest;
import com.confia.organization.domain.Modality;
import com.confia.organization.domain.ResourceNotFoundException;
import confia.generated.jooq.tables.records.OrganizationModalityRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;

/** The jOOQ adapter of {@link ModalityRepository} over {@code organization_modality}. */
public final class JooqModalityRepository implements ModalityRepository {

    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "code", ORGANIZATION_MODALITY.CODE,
            "name", ORGANIZATION_MODALITY.NAME);

    private final DSLContext dsl;

    public JooqModalityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Modality> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(ORGANIZATION_MODALITY)
                .where(ORGANIZATION_MODALITY.INSTITUTION_ID.eq(institutionId.value()))
                .and(ORGANIZATION_MODALITY.ID.eq(id))
                .fetchOptional().map(JooqModalityRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, Modality modality) {
        OrganizationWrites.translating(() -> dsl.insertInto(ORGANIZATION_MODALITY)
                .set(ORGANIZATION_MODALITY.INSTITUTION_ID, institutionId.value())
                .set(ORGANIZATION_MODALITY.ID, modality.id())
                .set(ORGANIZATION_MODALITY.CODE, modality.code())
                .set(ORGANIZATION_MODALITY.NAME, modality.name())
                .set(ORGANIZATION_MODALITY.IS_ACTIVE, modality.active())
                .set(ORGANIZATION_MODALITY.CREATED_AT, OrganizationWrites.toDatabase(modality.createdAt()))
                .set(ORGANIZATION_MODALITY.UPDATED_AT, OrganizationWrites.toDatabase(modality.updatedAt()))
                .execute());
    }

    @Override
    public void update(InstitutionId institutionId, Modality modality) {
        OrganizationWrites.translating(() -> {
            int rows = dsl.update(ORGANIZATION_MODALITY)
                    .set(ORGANIZATION_MODALITY.CODE, modality.code())
                    .set(ORGANIZATION_MODALITY.NAME, modality.name())
                    .set(ORGANIZATION_MODALITY.IS_ACTIVE, modality.active())
                    .set(ORGANIZATION_MODALITY.UPDATED_AT, OrganizationWrites.toDatabase(modality.updatedAt()))
                    .where(ORGANIZATION_MODALITY.INSTITUTION_ID.eq(institutionId.value()))
                    .and(ORGANIZATION_MODALITY.ID.eq(modality.id()))
                    .execute();
            if (rows == 0) {
                throw new ResourceNotFoundException();
            }
        });
    }

    @Override
    public Page<Modality> list(InstitutionId institutionId, PageRequest request) {
        var scope = ORGANIZATION_MODALITY.INSTITUTION_ID.eq(institutionId.value());
        long total = dsl.fetchCount(ORGANIZATION_MODALITY, scope);
        List<Modality> rows = dsl.selectFrom(ORGANIZATION_MODALITY).where(scope)
                .orderBy(order(request), ORGANIZATION_MODALITY.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqModalityRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(PageRequest request) {
        Field<?> field = SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static Modality toDomain(OrganizationModalityRecord row) {
        return new Modality(row.getId(), row.getCode(), row.getName(), row.getIsActive(),
                OrganizationWrites.fromDatabase(row.getCreatedAt()),
                OrganizationWrites.fromDatabase(row.getUpdatedAt()));
    }
}
