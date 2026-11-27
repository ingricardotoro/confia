package com.confia.scholarships.infrastructure;

import static confia.generated.jooq.tables.ScholarshipsDiscount.SCHOLARSHIPS_DISCOUNT;
import static confia.generated.jooq.tables.ScholarshipsDiscountConcept.SCHOLARSHIPS_DISCOUNT_CONCEPT;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Percentage;
import com.confia.scholarships.application.DiscountFilter;
import com.confia.scholarships.application.DiscountRepository;
import com.confia.scholarships.application.Page;
import com.confia.scholarships.application.PageRequest;
import com.confia.scholarships.domain.ApprovalStatus;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.BenefitType;
import com.confia.scholarships.domain.Discount;
import com.confia.scholarships.domain.DiscountProposal;
import com.confia.scholarships.domain.DiscountKind;
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
 * The jOOQ adapter of {@link DiscountRepository} over {@code scholarships_discount} and the concepts it covers. It inserts and reads,
 * and the one update it makes is of the editable columns under the version check: no statement here deletes a discount, and the
 * database refuses it to every role, as it refuses to change the benefit of one that is in use. The only rows it deletes are the
 * covered concepts that an edit drops, which are not a financial datum. Amounts go through {@link MoneyColumns} and nowhere else.
 */
public final class JooqDiscountRepository implements DiscountRepository {

    private final DSLContext dsl;

    public JooqDiscountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, Discount discount) {
        ScholarshipsWrites.writing(() -> {
            dsl.insertInto(SCHOLARSHIPS_DISCOUNT)
                    .set(SCHOLARSHIPS_DISCOUNT.INSTITUTION_ID, institution.value())
                    .set(SCHOLARSHIPS_DISCOUNT.ID, discount.id())
                    .set(SCHOLARSHIPS_DISCOUNT.CODE, discount.code())
                    .set(SCHOLARSHIPS_DISCOUNT.NAME, discount.name())
                    .set(SCHOLARSHIPS_DISCOUNT.KIND, discount.kind().code())
                    .set(SCHOLARSHIPS_DISCOUNT.BENEFIT_TYPE, discount.benefit().type().name())
                    .set(SCHOLARSHIPS_DISCOUNT.PERCENT,
                            discount.benefit().percent() == null ? null : discount.benefit().percent().value())
                    .set(SCHOLARSHIPS_DISCOUNT.FIXED_AMOUNT, discount.benefit().fixedAmount() == null ? null
                            : MoneyColumns.amountColumn(discount.benefit().fixedAmount()))
                    .set(SCHOLARSHIPS_DISCOUNT.CURRENCY, Benefit.CURRENCY.name())
                    .set(SCHOLARSHIPS_DISCOUNT.VALID_FROM, discount.validFrom())
                    .set(SCHOLARSHIPS_DISCOUNT.VALID_TO, discount.validTo())
                    .set(SCHOLARSHIPS_DISCOUNT.IS_STACKABLE, discount.stackable())
                    .set(SCHOLARSHIPS_DISCOUNT.IS_ACTIVE, discount.active())
                    .set(SCHOLARSHIPS_DISCOUNT.VERSION, discount.version())
                    .set(SCHOLARSHIPS_DISCOUNT.APPROVAL_STATUS, discount.approval().code())
                    .set(SCHOLARSHIPS_DISCOUNT.CREATED_BY, discount.createdBy())
                    .set(SCHOLARSHIPS_DISCOUNT.CREATED_AT, ScholarshipsWrites.toDatabase(discount.createdAt()))
                    .set(SCHOLARSHIPS_DISCOUNT.UPDATED_AT, ScholarshipsWrites.toDatabase(discount.updatedAt()))
                    .execute();
            insertConcepts(institution, discount.id(), discount.concepts());
        });
    }

    @Override
    public Optional<Discount> findById(InstitutionId institution, UUID id) {
        return findAll(institution, Set.of(id)).values().stream().findFirst();
    }

    @Override
    public Optional<Discount> lockById(InstitutionId institution, UUID id) {
        Optional<Record> locked = dsl.selectFrom(SCHOLARSHIPS_DISCOUNT)
                .where(SCHOLARSHIPS_DISCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_DISCOUNT.ID.eq(id)).forUpdate().fetchOptional().map(row -> (Record) row);
        return locked.map(row -> toDiscount(row, conceptsOf(institution, Set.of(id)).getOrDefault(id, Set.of())));
    }

    @Override
    public Map<UUID, Discount> findAll(InstitutionId institution, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> concepts = conceptsOf(institution, ids);
        Map<UUID, Discount> found = new HashMap<>();
        dsl.selectFrom(SCHOLARSHIPS_DISCOUNT).where(SCHOLARSHIPS_DISCOUNT.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_DISCOUNT.ID.in(ids))
                .fetch().forEach(row -> found.put(row.get(SCHOLARSHIPS_DISCOUNT.ID),
                        toDiscount(row, concepts.getOrDefault(row.get(SCHOLARSHIPS_DISCOUNT.ID), Set.of()))));
        return found;
    }

    @Override
    public void replace(InstitutionId institution, Discount after, long expectedVersion) {
        ScholarshipsWrites.writing(() -> {
            // The concepts first: an approved proposal adds its concepts while the row still carries it, and the row takes the terms
            // only once the concepts are the proposed ones.
            replaceConcepts(institution, after);
            DiscountProposal proposal = after.proposal();
            int updated = dsl.update(SCHOLARSHIPS_DISCOUNT)
                    .set(SCHOLARSHIPS_DISCOUNT.NAME, after.name())
                    .set(SCHOLARSHIPS_DISCOUNT.BENEFIT_TYPE, after.benefit().type().name())
                    .set(SCHOLARSHIPS_DISCOUNT.PERCENT,
                            after.benefit().percent() == null ? null : after.benefit().percent().value())
                    .set(SCHOLARSHIPS_DISCOUNT.FIXED_AMOUNT, after.benefit().fixedAmount() == null ? null
                            : MoneyColumns.amountColumn(after.benefit().fixedAmount()))
                    .set(SCHOLARSHIPS_DISCOUNT.VALID_FROM, after.validFrom())
                    .set(SCHOLARSHIPS_DISCOUNT.VALID_TO, after.validTo())
                    .set(SCHOLARSHIPS_DISCOUNT.IS_STACKABLE, after.stackable())
                    .set(SCHOLARSHIPS_DISCOUNT.IS_ACTIVE, after.active())
                    .set(SCHOLARSHIPS_DISCOUNT.VERSION, after.version())
                    .set(SCHOLARSHIPS_DISCOUNT.APPROVAL_STATUS, after.approval().code())
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_REQUESTED_BY, proposal == null ? null : proposal.requestedBy())
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_REQUESTED_AT,
                            proposal == null ? null : ScholarshipsWrites.toDatabase(proposal.requestedAt()))
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_NAME, proposal == null ? null : proposal.name())
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_BENEFIT_TYPE, proposal == null ? null : BenefitColumns.type(proposal.benefit()))
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_PERCENT, proposal == null ? null : BenefitColumns.percent(proposal.benefit()))
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_FIXED_AMOUNT,
                            proposal == null ? null : BenefitColumns.fixedAmount(proposal.benefit()))
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_VALID_FROM, proposal == null ? null : proposal.validFrom())
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_VALID_TO, proposal == null ? null : proposal.validTo())
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_IS_STACKABLE, proposal == null ? null : proposal.stackable())
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_REACTIVATE, proposal == null ? null : proposal.reactivate())
                    .set(SCHOLARSHIPS_DISCOUNT.PENDING_CONCEPTS,
                            proposal == null ? null : proposal.concepts().stream().sorted().toArray(UUID[]::new))
                    .set(SCHOLARSHIPS_DISCOUNT.UPDATED_AT, ScholarshipsWrites.toDatabase(after.updatedAt()))
                    .where(SCHOLARSHIPS_DISCOUNT.INSTITUTION_ID.eq(institution.value()))
                    .and(SCHOLARSHIPS_DISCOUNT.ID.eq(after.id()))
                    .and(SCHOLARSHIPS_DISCOUNT.VERSION.eq(expectedVersion)).execute();
            if (updated != 1) {
                throw new StaleVersionException();
            }
        });
    }

    /** The covered concepts become the ones of {@code after}: the ones dropped are deleted and the new ones inserted. */
    private void replaceConcepts(InstitutionId institution, Discount after) {
        Set<UUID> stored = conceptsOf(institution, Set.of(after.id())).getOrDefault(after.id(), Set.of());
        Set<UUID> dropped = new HashSet<>(stored);
        dropped.removeAll(after.concepts());
        if (!dropped.isEmpty()) {
            dsl.deleteFrom(SCHOLARSHIPS_DISCOUNT_CONCEPT)
                    .where(SCHOLARSHIPS_DISCOUNT_CONCEPT.INSTITUTION_ID.eq(institution.value()))
                    .and(SCHOLARSHIPS_DISCOUNT_CONCEPT.DISCOUNT_ID.eq(after.id()))
                    .and(SCHOLARSHIPS_DISCOUNT_CONCEPT.PAYMENT_CONCEPT_ID.in(dropped)).execute();
        }
        Set<UUID> added = new HashSet<>(after.concepts());
        added.removeAll(stored);
        insertConcepts(institution, after.id(), added);
    }

    @Override
    public Page<Discount> list(InstitutionId institution, DiscountFilter filter, PageRequest page) {
        Condition where = SCHOLARSHIPS_DISCOUNT.INSTITUTION_ID.eq(institution.value());
        if (filter.active() != null) {
            where = where.and(SCHOLARSHIPS_DISCOUNT.IS_ACTIVE.eq(filter.active()));
        }
        if (filter.kind() != null) {
            where = where.and(SCHOLARSHIPS_DISCOUNT.KIND.eq(filter.kind()));
        }
        if (filter.benefitType() != null) {
            where = where.and(SCHOLARSHIPS_DISCOUNT.BENEFIT_TYPE.eq(filter.benefitType()));
        }
        long total = dsl.selectCount().from(SCHOLARSHIPS_DISCOUNT).where(where).fetchOne(0, long.class);
        List<UUID> ids = dsl.select(SCHOLARSHIPS_DISCOUNT.ID).from(SCHOLARSHIPS_DISCOUNT).where(where)
                .orderBy(order(page), SCHOLARSHIPS_DISCOUNT.ID.asc()).limit(page.size()).offset(page.offset())
                .fetch(SCHOLARSHIPS_DISCOUNT.ID);
        Map<UUID, Discount> byId = findAll(institution, ids);
        return new Page<>(ids.stream().map(byId::get).toList(), total, page.page(), page.size());
    }

    /** The sort field was checked against {@code DiscountFilter.SORT_FIELDS} by the request; anything else is a defect. */
    private static OrderField<?> order(PageRequest page) {
        return switch (page.sortField()) {
            case "code" -> page.ascending() ? DSL.lower(SCHOLARSHIPS_DISCOUNT.CODE).asc()
                    : DSL.lower(SCHOLARSHIPS_DISCOUNT.CODE).desc();
            case "name" -> page.ascending() ? DSL.lower(SCHOLARSHIPS_DISCOUNT.NAME).asc()
                    : DSL.lower(SCHOLARSHIPS_DISCOUNT.NAME).desc();
            case "validFrom" -> page.ascending() ? SCHOLARSHIPS_DISCOUNT.VALID_FROM.asc() : SCHOLARSHIPS_DISCOUNT.VALID_FROM.desc();
            case "createdAt" -> page.ascending() ? SCHOLARSHIPS_DISCOUNT.CREATED_AT.asc()
                    : SCHOLARSHIPS_DISCOUNT.CREATED_AT.desc();
            default -> throw new IllegalArgumentException("unknown sort field");
        };
    }

    private void insertConcepts(InstitutionId institution, UUID discountId, Set<UUID> concepts) {
        for (UUID concept : concepts) {
            dsl.insertInto(SCHOLARSHIPS_DISCOUNT_CONCEPT)
                    .set(SCHOLARSHIPS_DISCOUNT_CONCEPT.INSTITUTION_ID, institution.value())
                    .set(SCHOLARSHIPS_DISCOUNT_CONCEPT.DISCOUNT_ID, discountId)
                    .set(SCHOLARSHIPS_DISCOUNT_CONCEPT.PAYMENT_CONCEPT_ID, concept).execute();
        }
    }

    private Map<UUID, Set<UUID>> conceptsOf(InstitutionId institution, Collection<UUID> discountIds) {
        Map<UUID, Set<UUID>> concepts = new HashMap<>();
        dsl.select(SCHOLARSHIPS_DISCOUNT_CONCEPT.DISCOUNT_ID, SCHOLARSHIPS_DISCOUNT_CONCEPT.PAYMENT_CONCEPT_ID)
                .from(SCHOLARSHIPS_DISCOUNT_CONCEPT)
                .where(SCHOLARSHIPS_DISCOUNT_CONCEPT.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_DISCOUNT_CONCEPT.DISCOUNT_ID.in(discountIds))
                .fetch().forEach(row -> concepts.computeIfAbsent(row.value1(), key -> new HashSet<>()).add(row.value2()));
        return concepts;
    }

    /** The proposal waiting in the row, or {@code null} when none waits. */
    private static DiscountProposal proposalOf(Record row, String currency) {
        UUID requestedBy = row.get(SCHOLARSHIPS_DISCOUNT.PENDING_REQUESTED_BY);
        if (requestedBy == null) {
            return null;
        }
        Benefit benefit = BenefitColumns.read(row.get(SCHOLARSHIPS_DISCOUNT.PENDING_BENEFIT_TYPE),
                row.get(SCHOLARSHIPS_DISCOUNT.PENDING_PERCENT), row.get(SCHOLARSHIPS_DISCOUNT.PENDING_FIXED_AMOUNT), currency);
        return new DiscountProposal(row.get(SCHOLARSHIPS_DISCOUNT.PENDING_NAME), benefit,
                row.get(SCHOLARSHIPS_DISCOUNT.PENDING_VALID_FROM), row.get(SCHOLARSHIPS_DISCOUNT.PENDING_VALID_TO),
                row.get(SCHOLARSHIPS_DISCOUNT.PENDING_IS_STACKABLE), row.get(SCHOLARSHIPS_DISCOUNT.PENDING_REACTIVATE),
                Set.of(row.get(SCHOLARSHIPS_DISCOUNT.PENDING_CONCEPTS)),
                requestedBy, ScholarshipsWrites.fromDatabase(row.get(SCHOLARSHIPS_DISCOUNT.PENDING_REQUESTED_AT)));
    }

    private static Discount toDiscount(Record row, Set<UUID> concepts) {
        BenefitType type = BenefitType.valueOf(row.get(SCHOLARSHIPS_DISCOUNT.BENEFIT_TYPE));
        String currency = row.get(SCHOLARSHIPS_DISCOUNT.CURRENCY);
        Benefit benefit = type == BenefitType.PERCENTAGE
                ? new Benefit(type, Percentage.of(row.get(SCHOLARSHIPS_DISCOUNT.PERCENT)), null)
                : new Benefit(type, null, MoneyColumns.toMoney(row.get(SCHOLARSHIPS_DISCOUNT.FIXED_AMOUNT), currency));
        OffsetDateTime created = row.get(SCHOLARSHIPS_DISCOUNT.CREATED_AT);
        OffsetDateTime updated = row.get(SCHOLARSHIPS_DISCOUNT.UPDATED_AT);
        return new Discount(row.get(SCHOLARSHIPS_DISCOUNT.ID), row.get(SCHOLARSHIPS_DISCOUNT.CODE),
                row.get(SCHOLARSHIPS_DISCOUNT.NAME), DiscountKind.ofCode(row.get(SCHOLARSHIPS_DISCOUNT.KIND)).orElseThrow(),
                benefit, row.get(SCHOLARSHIPS_DISCOUNT.VALID_FROM), row.get(SCHOLARSHIPS_DISCOUNT.VALID_TO), concepts,
                row.get(SCHOLARSHIPS_DISCOUNT.IS_STACKABLE), row.get(SCHOLARSHIPS_DISCOUNT.IS_ACTIVE),
                row.get(SCHOLARSHIPS_DISCOUNT.VERSION), row.get(SCHOLARSHIPS_DISCOUNT.CREATED_BY),
                ScholarshipsWrites.fromDatabase(created), ScholarshipsWrites.fromDatabase(updated),
                ApprovalStatus.ofCode(row.get(SCHOLARSHIPS_DISCOUNT.APPROVAL_STATUS)).orElseThrow(), proposalOf(row, currency));
    }
}
