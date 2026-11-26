package com.confia.scholarships.infrastructure;

import static confia.generated.jooq.tables.ScholarshipsAward.SCHOLARSHIPS_AWARD;
import static confia.generated.jooq.tables.ScholarshipsScholarship.SCHOLARSHIPS_SCHOLARSHIP;
import static confia.generated.jooq.tables.ScholarshipsScholarshipConcept.SCHOLARSHIPS_SCHOLARSHIP_CONCEPT;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Percentage;
import com.confia.scholarships.application.AwardsInForceRepository;
import com.confia.scholarships.domain.AwardStatus;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.BenefitType;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.time.LocalDate;
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
 * The jOOQ adapter of {@link AwardsInForceRepository}: read-only queries over the awards, the scholarships and the concepts they cover,
 * optionally locked {@code FOR SHARE}. It never opens a transaction and never sets the institution of the session; row-level security
 * does the second filter. Amounts go through {@link MoneyColumns} and nowhere else.
 */
public final class JooqAwardsInForceRepository implements AwardsInForceRepository {

    private final DSLContext dsl;

    public JooqAwardsInForceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<InForce> find(InstitutionId institution, UUID studentId, UUID paymentConceptId, LocalDate on,
            boolean lockShare) {
        SelectForUpdateStep<?> query = dsl.select(SCHOLARSHIPS_AWARD.ID, SCHOLARSHIPS_SCHOLARSHIP.CODE,
                SCHOLARSHIPS_SCHOLARSHIP.BENEFIT_TYPE, SCHOLARSHIPS_SCHOLARSHIP.PERCENT,
                SCHOLARSHIPS_SCHOLARSHIP.FIXED_AMOUNT, SCHOLARSHIPS_SCHOLARSHIP.PERIOD_CAP,
                SCHOLARSHIPS_SCHOLARSHIP.CURRENCY)
                .from(SCHOLARSHIPS_AWARD)
                .join(SCHOLARSHIPS_SCHOLARSHIP)
                .on(SCHOLARSHIPS_SCHOLARSHIP.INSTITUTION_ID.eq(SCHOLARSHIPS_AWARD.INSTITUTION_ID)
                        .and(SCHOLARSHIPS_SCHOLARSHIP.ID.eq(SCHOLARSHIPS_AWARD.SCHOLARSHIP_ID)))
                .where(inForce(institution, on))
                .and(SCHOLARSHIPS_AWARD.STUDENT_ID.eq(studentId))
                .and(covers(institution, List.of(paymentConceptId)))
                .orderBy(SCHOLARSHIPS_SCHOLARSHIP.CODE, SCHOLARSHIPS_AWARD.ID);
        ResultQuery<?> locked = lockShare ? query.forShare().of(SCHOLARSHIPS_AWARD, SCHOLARSHIPS_SCHOLARSHIP) : query;
        return locked.fetch(row -> toInForce(row));
    }

    @Override
    public boolean anyCovering(InstitutionId institution, Collection<UUID> studentIds, Collection<UUID> paymentConceptIds,
            LocalDate on) {
        if (studentIds.isEmpty() || paymentConceptIds.isEmpty()) {
            return false;
        }
        return dsl.fetchExists(dsl.selectOne().from(SCHOLARSHIPS_AWARD)
                .join(SCHOLARSHIPS_SCHOLARSHIP)
                .on(SCHOLARSHIPS_SCHOLARSHIP.INSTITUTION_ID.eq(SCHOLARSHIPS_AWARD.INSTITUTION_ID)
                        .and(SCHOLARSHIPS_SCHOLARSHIP.ID.eq(SCHOLARSHIPS_AWARD.SCHOLARSHIP_ID)))
                .where(inForce(institution, on))
                .and(SCHOLARSHIPS_AWARD.STUDENT_ID.in(studentIds))
                .and(covers(institution, paymentConceptIds)));
    }

    /** Approved, in force on the date with both ends included, of an active scholarship, in the institution. */
    private static Condition inForce(InstitutionId institution, LocalDate on) {
        return SCHOLARSHIPS_AWARD.INSTITUTION_ID.eq(institution.value())
                .and(SCHOLARSHIPS_AWARD.STATUS.eq(AwardStatus.APPROVED.code()))
                .and(SCHOLARSHIPS_AWARD.VALID_FROM.le(on))
                .and(SCHOLARSHIPS_AWARD.VALID_TO.ge(on))
                .and(SCHOLARSHIPS_SCHOLARSHIP.IS_ACTIVE.isTrue());
    }

    /** The scholarship covers at least one of the concepts. */
    private static Condition covers(InstitutionId institution, Collection<UUID> paymentConceptIds) {
        return DSL.exists(DSL.selectOne().from(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT)
                .where(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.SCHOLARSHIP_ID.eq(SCHOLARSHIPS_AWARD.SCHOLARSHIP_ID))
                .and(SCHOLARSHIPS_SCHOLARSHIP_CONCEPT.PAYMENT_CONCEPT_ID.in(paymentConceptIds)));
    }

    private static InForce toInForce(Record row) {
        BenefitType type = BenefitType.valueOf(row.get(SCHOLARSHIPS_SCHOLARSHIP.BENEFIT_TYPE));
        String currency = row.get(SCHOLARSHIPS_SCHOLARSHIP.CURRENCY);
        Benefit benefit = type == BenefitType.PERCENTAGE
                ? new Benefit(type, Percentage.of(row.get(SCHOLARSHIPS_SCHOLARSHIP.PERCENT)), null)
                : new Benefit(type, null, MoneyColumns.toMoney(row.get(SCHOLARSHIPS_SCHOLARSHIP.FIXED_AMOUNT), currency));
        var cap = row.get(SCHOLARSHIPS_SCHOLARSHIP.PERIOD_CAP);
        return new InForce(row.get(SCHOLARSHIPS_AWARD.ID), row.get(SCHOLARSHIPS_SCHOLARSHIP.CODE), benefit,
                cap == null ? null : MoneyColumns.toMoney(cap, currency));
    }
}
