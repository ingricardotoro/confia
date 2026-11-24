package com.confia.scholarships.infrastructure;

import static confia.generated.jooq.tables.ScholarshipsScholarship.SCHOLARSHIPS_SCHOLARSHIP;
import static confia.generated.jooq.tables.ScholarshipsScholarshipConcept.SCHOLARSHIPS_SCHOLARSHIP_CONCEPT;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Percentage;
import com.confia.scholarships.application.Page;
import com.confia.scholarships.application.PageRequest;
import com.confia.scholarships.application.ScholarshipFilter;
import com.confia.scholarships.application.ScholarshipRepository;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.BenefitType;
import com.confia.scholarships.domain.Scholarship;
import com.confia.scholarships.domain.StaleVersionException;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.OrderField;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link ScholarshipRepository} over {@code scholarships_scholarship} and the concepts it covers. It inserts and
 * reads, and the one update it makes is of the editable columns under the version check: no statement here deletes a scholarship, and
 * the database refuses it to every role, as it refuses to change the benefit of one that has approved awards. The only rows it
 * deletes are the covered concepts that an edit drops, which are not a financial datum. Amounts go through {@link MoneyColumns}
 * and nowhere else.
 */
public final class JooqScholarshipRepository implements ScholarshipRepository {

    private final DSLContext dsl;

    public JooqScholarshipRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, Scholarship scholarship) {
        ScholarshipsWrites.writing(() -> {
            dsl.insertInto(SCHOLARSHIPS_SCHOLARSHIP)
                    .set(SCHOLARSHIPS_SCHOLARSHIP.INSTITUTION_ID, institution.value())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.ID, scholarship.id())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.CODE, scholarship.code())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.NAME, scholarship.name())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.BENEFIT_TYPE, scholarship.benefit().type().name())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.PERCENT,
                            scholarship.benefit().percent() == null ? null : scholarship.benefit().percent().value())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.FIXED_AMOUNT, scholarship.benefit().fixedAmount() == null ? null
                            : MoneyColumns.amountColumn(scholarship.benefit().fixedAmount()))
                    .set(SCHOLARSHIPS_SCHOLARSHIP.PERIOD_CAP,
                            scholarship.periodCap() == null ? null : MoneyColumns.amountColumn(scholarship.periodCap()))
                    .set(SCHOLARSHIPS_SCHOLARSHIP.CURRENCY, Benefit.CURRENCY.name())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.IS_ACTIVE, scholarship.active())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.VERSION, scholarship.version())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.CREATED_BY, scholarship.createdBy())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.CREATED_AT, ScholarshipsWrites.toDatabase(scholarship.createdAt()))
                    .set(SCHOLARSHIPS_SCHOLARSHIP.UPDATED_AT, ScholarshipsWrites.toDatabase(scholarship.updatedAt()))
                    .execute();
            insertConcepts(institution, scholarship.id(), scholarship.concepts());
        });
    }

    @Override
    public Optional<Scholarship> findById(InstitutionId institution, UUID id) {
        return findAll(institution, Set.of(id)).values().stream().findFirst();
    }

    @Override
    public Optional<Scholarship> lockById(InstitutionId institution, UUID id) {
        Optional<Record> locked = dsl.selectFrom(SCHOLARSHIPS_SCHOLARSHIP)
                .where(SCHOLARSHIPS_SCHOLARSHIP.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_SCHOLARSHIP.ID.eq(id)).forUpdate().fetchOptional().map(row -> (Record) row);
        return locked.map(row -> toScholarship(row, conceptsOf(institution, Set.of(id)).getOrDefault(id, Set.of())));
    }

    @Override
    public Map<UUID, Scholarship> findAll(InstitutionId institution, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> concepts = conceptsOf(institution, ids);
        Map<UUID, Scholarship> found = new HashMap<>();
        dsl.selectFrom(SCHOLARSHIPS_SCHOLARSHIP).where(SCHOLARSHIPS_SCHOLARSHIP.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_SCHOLARSHIP.ID.in(ids))
                .fetch().forEach(row -> found.put(row.get(SCHOLARSHIPS_SCHOLARSHIP.ID),
                        toScholarship(row, concepts.getOrDefault(row.get(SCHOLARSHIPS_SCHOLARSHIP.ID), Set.of()))));
        return found;
    }

    @Override
    public void replace(InstitutionId institution, Scholarship after, long expectedVersion) {
        ScholarshipsWrites.writing(() -> {
            int updated = dsl.update(SCHOLARSHIPS_SCHOLARSHIP)
                    .set(SCHOLARSHIPS_SCHOLARSHIP.NAME, after.name())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.BENEFIT_TYPE, after.benefit().type().name())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.PERCENT,
                            after.benefit().percent() == null ? null : after.benefit().percent().value())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.FIXED_AMOUNT, after.benefit().fixedAmount() == null ? null
                            : MoneyColumns.amountColumn(after.benefit().fixedAmount()))
                    .set(SCHOLARSHIPS_SCHOLARSHIP.PERIOD_CAP,
                            after.periodCap() == null ? null : MoneyColumns.amountColumn(after.periodCap()))
                    .set(SCHOLARSHIPS_SCHOLARSHIP.IS_ACTIVE, after.active())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.VERSION, after.version())
                    .set(SCHOLARSHIPS_SCHOLARSHIP.UPDATED_AT, ScholarshipsWrites.toDatabase(after.updatedAt()))
                    .where(SCHOLARSHIPS_SCHOLARSHIP.INSTITUTION_ID.eq(institution.value()))
                    .and(SCHOLARSHIPS_SCHOLARSHIP.ID.eq(after.id()))
                    .and(SCHOLARSHIPS_SCHOLARSHIP.VERSION.eq(expectedVersion)).execute();
            if (updated != 1) {
                throw new StaleVersionException();
            }
            Set<UUID> stored = conceptsOf(institution, Set.of(after.id())).getOrDefault(after.id(), Set.of());
            Set<UUID> dropped = new HashSet<>(stored);
            dropped.removeAll(after.concepts());
            if (!dropped.isEmpty()) {
                dsl.deleteFrom(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT)
                        .where(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.INSTITUTION_ID.eq(institution.value()))
                        .and(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.SCHOLARSHIP_ID.eq(after.id()))
                        .and(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.PAYMENT_CONCEPT_ID.in(dropped)).execute();
            }
            Set<UUID> added = new HashSet<>(after.concepts());
            added.removeAll(stored);
            insertConcepts(institution, after.id(), added);
        });
    }

    @Override
    public Page<Scholarship> list(InstitutionId institution, ScholarshipFilter filter, PageRequest page) {
        Condition where = SCHOLARSHIPS_SCHOLARSHIP.INSTITUTION_ID.eq(institution.value());
        if (filter.active() != null) {
            where = where.and(SCHOLARSHIPS_SCHOLARSHIP.IS_ACTIVE.eq(filter.active()));
        }
        if (filter.benefitType() != null) {
            where = where.and(SCHOLARSHIPS_SCHOLARSHIP.BENEFIT_TYPE.eq(filter.benefitType()));
        }
        long total = dsl.selectCount().from(SCHOLARSHIPS_SCHOLARSHIP).where(where).fetchOne(0, long.class);
        List<UUID> ids = dsl.select(SCHOLARSHIPS_SCHOLARSHIP.ID).from(SCHOLARSHIPS_SCHOLARSHIP).where(where)
                .orderBy(order(page), SCHOLARSHIPS_SCHOLARSHIP.ID.asc()).limit(page.size()).offset(page.offset())
                .fetch(SCHOLARSHIPS_SCHOLARSHIP.ID);
        Map<UUID, Scholarship> byId = findAll(institution, ids);
        return new Page<>(ids.stream().map(byId::get).toList(), total, page.page(), page.size());
    }

    /** The sort field was checked against {@code ScholarshipFilter.SORT_FIELDS} by the request; anything else is a defect. */
    private static OrderField<?> order(PageRequest page) {
        return switch (page.sortField()) {
            case "code" -> page.ascending() ? DSL.lower(SCHOLARSHIPS_SCHOLARSHIP.CODE).asc()
                    : DSL.lower(SCHOLARSHIPS_SCHOLARSHIP.CODE).desc();
            case "name" -> page.ascending() ? DSL.lower(SCHOLARSHIPS_SCHOLARSHIP.NAME).asc()
                    : DSL.lower(SCHOLARSHIPS_SCHOLARSHIP.NAME).desc();
            case "createdAt" -> page.ascending() ? SCHOLARSHIPS_SCHOLARSHIP.CREATED_AT.asc()
                    : SCHOLARSHIPS_SCHOLARSHIP.CREATED_AT.desc();
            default -> throw new IllegalArgumentException("unknown sort field");
        };
    }

    private void insertConcepts(InstitutionId institution, UUID scholarshipId, Set<UUID> concepts) {
        for (UUID concept : concepts) {
            dsl.insertInto(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT)
                    .set(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.INSTITUTION_ID, institution.value())
                    .set(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.SCHOLARSHIP_ID, scholarshipId)
                    .set(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.PAYMENT_CONCEPT_ID, concept).execute();
        }
    }

    private Map<UUID, Set<UUID>> conceptsOf(InstitutionId institution, Collection<UUID> scholarshipIds) {
        Map<UUID, Set<UUID>> concepts = new HashMap<>();
        dsl.select(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.SCHOLARSHIP_ID, SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.PAYMENT_CONCEPT_ID)
                .from(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT)
                .where(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.SCHOLARSHIP_ID.in(scholarshipIds))
                .fetch().forEach(row -> concepts.computeIfAbsent(row.value1(), key -> new HashSet<>()).add(row.value2()));
        return concepts;
    }

    private static Scholarship toScholarship(Record row, Set<UUID> concepts) {
        BenefitType type = BenefitType.valueOf(row.get(SCHOLARSHIPS_SCHOLARSHIP.BENEFIT_TYPE));
        String currency = row.get(SCHOLARSHIPS_SCHOLARSHIP.CURRENCY);
        Benefit benefit = type == BenefitType.PERCENTAGE
                ? new Benefit(type, Percentage.of(row.get(SCHOLARSHIPS_SCHOLARSHIP.PERCENT)), null)
                : new Benefit(type, null, MoneyColumns.toMoney(row.get(SCHOLARSHIPS_SCHOLARSHIP.FIXED_AMOUNT), currency));
        var cap = row.get(SCHOLARSHIPS_SCHOLARSHIP.PERIOD_CAP);
        OffsetDateTime created = row.get(SCHOLARSHIPS_SCHOLARSHIP.CREATED_AT);
        OffsetDateTime updated = row.get(SCHOLARSHIPS_SCHOLARSHIP.UPDATED_AT);
        return new Scholarship(row.get(SCHOLARSHIPS_SCHOLARSHIP.ID), row.get(SCHOLARSHIPS_SCHOLARSHIP.CODE),
                row.get(SCHOLARSHIPS_SCHOLARSHIP.NAME), benefit, cap == null ? null : MoneyColumns.toMoney(cap, currency),
                concepts, row.get(SCHOLARSHIPS_SCHOLARSHIP.IS_ACTIVE), row.get(SCHOLARSHIPS_SCHOLARSHIP.VERSION),
                row.get(SCHOLARSHIPS_SCHOLARSHIP.CREATED_BY), ScholarshipsWrites.fromDatabase(created),
                ScholarshipsWrites.fromDatabase(updated));
    }
}
