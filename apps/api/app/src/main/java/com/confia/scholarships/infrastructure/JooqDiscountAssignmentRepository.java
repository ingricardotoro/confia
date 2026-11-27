package com.confia.scholarships.infrastructure;

import static confia.generated.jooq.tables.ScholarshipsDiscountAssignment.SCHOLARSHIPS_DISCOUNT_ASSIGNMENT;
import static confia.generated.jooq.tables.ScholarshipsDiscountInUse.SCHOLARSHIPS_DISCOUNT_IN_USE;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.application.AssignmentFilter;
import com.confia.scholarships.application.DiscountAssignmentRepository;
import com.confia.scholarships.application.Page;
import com.confia.scholarships.application.PageRequest;
import com.confia.scholarships.domain.AwardStatus;
import com.confia.scholarships.domain.DiscountAssignment;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.OrderField;
import org.jooq.Record;

/**
 * The jOOQ adapter of {@link DiscountAssignmentRepository} over {@code scholarships_discount_assignment}. It inserts and reads, and
 * the one update it makes is the forward move of the status: no statement here edits another column or deletes an assignment, and the
 * database refuses both to every role, as it refuses two approved assignments of a discount for one student with overlapping
 * validities.
 */
public final class JooqDiscountAssignmentRepository implements DiscountAssignmentRepository {

    private final DSLContext dsl;

    public JooqDiscountAssignmentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, DiscountAssignment assignment) {
        ScholarshipsWrites.writing(() -> dsl.insertInto(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT)
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.INSTITUTION_ID, institution.value())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.ID, assignment.id())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.DISCOUNT_ID, assignment.discountId())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STUDENT_ID, assignment.studentId())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.VALID_FROM, assignment.validFrom())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.VALID_TO, assignment.validTo())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STATUS, assignment.status().code())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.REQUESTED_BY, assignment.requestedBy())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.REQUESTED_AT, ScholarshipsWrites.toDatabase(assignment.requestedAt()))
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.DISCOUNT_VERSION, assignment.discountVersion())
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.BENEFIT_TYPE, BenefitColumns.type(assignment.benefit()))
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.PERCENT, BenefitColumns.percent(assignment.benefit()))
                .set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.FIXED_AMOUNT, BenefitColumns.fixedAmount(assignment.benefit()))
                .execute());
    }

    @Override
    public Optional<DiscountAssignment> findById(InstitutionId institution, UUID id) {
        return dsl.selectFrom(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT)
                .where(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.ID.eq(id)).fetchOptional(JooqDiscountAssignmentRepository::toAssignment);
    }

    @Override
    public Optional<DiscountAssignment> findForUpdate(InstitutionId institution, UUID id) {
        return dsl.selectFrom(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT)
                .where(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.ID.eq(id)).forUpdate()
                .fetchOptional(JooqDiscountAssignmentRepository::toAssignment);
    }

    @Override
    public void advanceStatus(InstitutionId institution, UUID id, AwardStatus to) {
        ScholarshipsWrites.writing(() -> {
            int updated = dsl.update(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT).set(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STATUS, to.code())
                    .where(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.INSTITUTION_ID.eq(institution.value()))
                    .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.ID.eq(id)).execute();
            if (updated != 1) {
                // The caller holds the row locked: anything but one row is a defect, never a decision that silently did nothing.
                throw new IllegalStateException("the status of an assignment moves in exactly one row");
            }
        });
    }

    @Override
    public Set<UUID> inUse(InstitutionId institution, Collection<UUID> discountIds) {
        if (discountIds.isEmpty()) {
            return Set.of();
        }
        // In use: an assignment was approved (and maybe revoked since) or a charge was issued with the discount. The view answers both,
        // because the second fact lives in the tables of the charges, which this module never reads.
        return new HashSet<>(dsl.select(SCHOLARSHIPS_DISCOUNT_IN_USE.DISCOUNT_ID)
                .from(SCHOLARSHIPS_DISCOUNT_IN_USE)
                .where(SCHOLARSHIPS_DISCOUNT_IN_USE.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_DISCOUNT_IN_USE.DISCOUNT_ID.in(discountIds))
                .fetch(SCHOLARSHIPS_DISCOUNT_IN_USE.DISCOUNT_ID));
    }

    @Override
    public Page<DiscountAssignment> list(InstitutionId institution, AssignmentFilter filter, PageRequest page) {
        Condition where = SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.INSTITUTION_ID.eq(institution.value());
        if (filter.studentId() != null) {
            where = where.and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STUDENT_ID.eq(filter.studentId()));
        }
        if (filter.status() != null) {
            where = where.and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STATUS.eq(filter.status()));
        }
        if (filter.discountId() != null) {
            where = where.and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.DISCOUNT_ID.eq(filter.discountId()));
        }
        long total = dsl.selectCount().from(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT).where(where).fetchOne(0, long.class);
        List<DiscountAssignment> rows = dsl.selectFrom(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT).where(where)
                .orderBy(order(page), SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.ID.asc()).limit(page.size()).offset(page.offset())
                .fetch(JooqDiscountAssignmentRepository::toAssignment);
        return new Page<>(rows, total, page.page(), page.size());
    }

    /** The sort field was checked against {@code AssignmentFilter.SORT_FIELDS} by the request; anything else is a defect. */
    private static OrderField<?> order(PageRequest page) {
        var field = switch (page.sortField()) {
            case "requestedAt" -> SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.REQUESTED_AT;
            case "validFrom" -> SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.VALID_FROM;
            case "validTo" -> SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.VALID_TO;
            default -> throw new IllegalArgumentException("unknown sort field");
        };
        return page.ascending() ? field.asc() : field.desc();
    }

    private static DiscountAssignment toAssignment(Record row) {
        return new DiscountAssignment(row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.ID),
                row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.DISCOUNT_ID), row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STUDENT_ID),
                row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.VALID_FROM), row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.VALID_TO),
                AwardStatus.ofCode(row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STATUS)).orElseThrow(),
                row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.REQUESTED_BY),
                ScholarshipsWrites.fromDatabase(row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.REQUESTED_AT)),
                row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.DISCOUNT_VERSION),
                BenefitColumns.read(row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.BENEFIT_TYPE),
                        row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.PERCENT), row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.FIXED_AMOUNT),
                        "HNL"));
    }
}
