package com.confia.scholarships.infrastructure;

import static confia.generated.jooq.tables.ScholarshipsDiscount.SCHOLARSHIPS_DISCOUNT;
import static confia.generated.jooq.tables.ScholarshipsDiscountAssignment.SCHOLARSHIPS_DISCOUNT_ASSIGNMENT;
import static confia.generated.jooq.tables.ScholarshipsDiscountConcept.SCHOLARSHIPS_DISCOUNT_CONCEPT;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Percentage;
import com.confia.scholarships.application.DiscountsInForceRepository;
import com.confia.scholarships.domain.AwardStatus;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.BenefitType;
import com.confia.scholarships.domain.DiscountKind;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.SelectForUpdateStep;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link DiscountsInForceRepository}: read-only queries over the discounts, the concepts they cover and the
 * assignments of the agreement ones, optionally locked {@code FOR SHARE}. It never opens a transaction and never sets the institution
 * of the session; row-level security does the second filter. Amounts go through {@link MoneyColumns} and nowhere else.
 */
public final class JooqDiscountsInForceRepository implements DiscountsInForceRepository {

    private final DSLContext dsl;

    public JooqDiscountsInForceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<InForce> find(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on,
            boolean lockShare) {
        SelectForUpdateStep<?> campaign = dsl.select(SCHOLARSHIPS_DISCOUNT.ID, SCHOLARSHIPS_DISCOUNT.CODE,
                SCHOLARSHIPS_DISCOUNT.BENEFIT_TYPE, SCHOLARSHIPS_DISCOUNT.PERCENT, SCHOLARSHIPS_DISCOUNT.FIXED_AMOUNT,
                SCHOLARSHIPS_DISCOUNT.CURRENCY, SCHOLARSHIPS_DISCOUNT.IS_STACKABLE)
                .from(SCHOLARSHIPS_DISCOUNT)
                .where(inForce(institution, on))
                .and(SCHOLARSHIPS_DISCOUNT.KIND.eq(DiscountKind.CAMPAIGN.code()))
                .and(covers(institution, List.of(paymentConceptId)));
        SelectForUpdateStep<?> agreement = dsl.select(SCHOLARSHIPS_DISCOUNT.ID, SCHOLARSHIPS_DISCOUNT.CODE,
                SCHOLARSHIPS_DISCOUNT.BENEFIT_TYPE, SCHOLARSHIPS_DISCOUNT.PERCENT, SCHOLARSHIPS_DISCOUNT.FIXED_AMOUNT,
                SCHOLARSHIPS_DISCOUNT.CURRENCY, SCHOLARSHIPS_DISCOUNT.IS_STACKABLE, SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.ID)
                .from(SCHOLARSHIPS_DISCOUNT)
                .join(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT)
                .on(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.INSTITUTION_ID.eq(SCHOLARSHIPS_DISCOUNT.INSTITUTION_ID)
                        .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.DISCOUNT_ID.eq(SCHOLARSHIPS_DISCOUNT.ID)))
                .where(inForce(institution, on))
                .and(SCHOLARSHIPS_DISCOUNT.KIND.eq(DiscountKind.AGREEMENT.code()))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STUDENT_ID.eq(studentId))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STATUS.eq(AwardStatus.APPROVED.code()))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.VALID_FROM.le(on))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.VALID_TO.ge(on))
                .and(covers(institution, List.of(paymentConceptId)));
        List<InForce> found = new ArrayList<>();
        found.addAll(run(lockShare ? campaign.forShare().of(SCHOLARSHIPS_DISCOUNT) : campaign));
        found.addAll(run(lockShare ? agreement.forShare().of(SCHOLARSHIPS_DISCOUNT, SCHOLARSHIPS_DISCOUNT_ASSIGNMENT)
                : agreement));
        return found;
    }

    @Override
    public boolean anyCovering(InstitutionId institution, Collection<UUID> studentIds, Collection<UUID> paymentConceptIds,
            LocalDate on) {
        if (studentIds.isEmpty() || paymentConceptIds.isEmpty()) {
            return false;
        }
        boolean campaign = dsl.fetchExists(dsl.selectOne().from(SCHOLARSHIPS_DISCOUNT)
                .where(inForce(institution, on))
                .and(SCHOLARSHIPS_DISCOUNT.KIND.eq(DiscountKind.CAMPAIGN.code()))
                .and(covers(institution, paymentConceptIds)));
        if (campaign) {
            return true;
        }
        return dsl.fetchExists(dsl.selectOne().from(SCHOLARSHIPS_DISCOUNT)
                .join(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT)
                .on(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.INSTITUTION_ID.eq(SCHOLARSHIPS_DISCOUNT.INSTITUTION_ID)
                        .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.DISCOUNT_ID.eq(SCHOLARSHIPS_DISCOUNT.ID)))
                .where(inForce(institution, on))
                .and(SCHOLARSHIPS_DISCOUNT.KIND.eq(DiscountKind.AGREEMENT.code()))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STUDENT_ID.in(studentIds))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.STATUS.eq(AwardStatus.APPROVED.code()))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.VALID_FROM.le(on))
                .and(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.VALID_TO.ge(on))
                .and(covers(institution, paymentConceptIds)));
    }

    private static List<InForce> run(ResultQuery<?> query) {
        return query.fetch(row -> toInForce(row));
    }

    /** Active and with a validity that includes the day, both ends included, in the institution. */
    private static Condition inForce(InstitutionId institution, LocalDate on) {
        return SCHOLARSHIPS_DISCOUNT.INSTITUTION_ID.eq(institution.value())
                .and(SCHOLARSHIPS_DISCOUNT.IS_ACTIVE.isTrue())
                .and(SCHOLARSHIPS_DISCOUNT.VALID_FROM.le(on))
                .and(SCHOLARSHIPS_DISCOUNT.VALID_TO.ge(on));
    }

    /** The discount covers at least one of the concepts. */
    private static Condition covers(InstitutionId institution, Collection<UUID> paymentConceptIds) {
        return DSL.exists(DSL.selectOne().from(SCHOLARSHIPS_DISCOUNT_CONCEPT)
                .where(SCHOLARSHIPS_DISCOUNT_CONCEPT.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_DISCOUNT_CONCEPT.DISCOUNT_ID.eq(SCHOLARSHIPS_DISCOUNT.ID))
                .and(SCHOLARSHIPS_DISCOUNT_CONCEPT.PAYMENT_CONCEPT_ID.in(paymentConceptIds)));
    }

    private static InForce toInForce(Record row) {
        BenefitType type = BenefitType.valueOf(row.get(SCHOLARSHIPS_DISCOUNT.BENEFIT_TYPE));
        String currency = row.get(SCHOLARSHIPS_DISCOUNT.CURRENCY);
        Benefit benefit = type == BenefitType.PERCENTAGE
                ? new Benefit(type, Percentage.of(row.get(SCHOLARSHIPS_DISCOUNT.PERCENT)), null)
                : new Benefit(type, null, MoneyColumns.toMoney(row.get(SCHOLARSHIPS_DISCOUNT.FIXED_AMOUNT), currency));
        // The campaign query does not select the assignment: the field is absent from its rows.
        UUID assignmentId = row.field(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.ID) == null ? null
                : row.get(SCHOLARSHIPS_DISCOUNT_ASSIGNMENT.ID);
        return new InForce(row.get(SCHOLARSHIPS_DISCOUNT.ID), row.get(SCHOLARSHIPS_DISCOUNT.CODE), assignmentId,
                row.get(SCHOLARSHIPS_DISCOUNT.IS_STACKABLE), benefit);
    }
}
