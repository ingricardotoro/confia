package com.confia.scholarships.infrastructure;

import static confia.generated.jooq.tables.ScholarshipsAward.SCHOLARSHIPS_AWARD;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.application.AwardFilter;
import com.confia.scholarships.application.AwardRepository;
import com.confia.scholarships.application.Page;
import com.confia.scholarships.application.PageRequest;
import com.confia.scholarships.domain.AwardStatus;
import com.confia.scholarships.domain.ScholarshipAward;
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
 * The jOOQ adapter of {@link AwardRepository} over {@code scholarships_award}. It inserts and reads, and the one update it makes is
 * the forward move of the status: no statement here edits another column or deletes an award, and the database refuses both to every
 * role, as it refuses two approved awards of a scholarship for one student with overlapping validities.
 */
public final class JooqAwardRepository implements AwardRepository {

    private final DSLContext dsl;

    public JooqAwardRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institution, ScholarshipAward award) {
        ScholarshipsWrites.writing(() -> dsl.insertInto(SCHOLARSHIPS_AWARD)
                .set(SCHOLARSHIPS_AWARD.INSTITUTION_ID, institution.value())
                .set(SCHOLARSHIPS_AWARD.ID, award.id())
                .set(SCHOLARSHIPS_AWARD.SCHOLARSHIP_ID, award.scholarshipId())
                .set(SCHOLARSHIPS_AWARD.STUDENT_ID, award.studentId())
                .set(SCHOLARSHIPS_AWARD.ACADEMIC_YEAR_ID, award.academicYearId())
                .set(SCHOLARSHIPS_AWARD.VALID_FROM, award.validFrom())
                .set(SCHOLARSHIPS_AWARD.VALID_TO, award.validTo())
                .set(SCHOLARSHIPS_AWARD.STATUS, award.status().code())
                .set(SCHOLARSHIPS_AWARD.REQUESTED_BY, award.requestedBy())
                .set(SCHOLARSHIPS_AWARD.REQUESTED_AT, ScholarshipsWrites.toDatabase(award.requestedAt()))
                .execute());
    }

    @Override
    public Optional<ScholarshipAward> findById(InstitutionId institution, UUID id) {
        return dsl.selectFrom(SCHOLARSHIPS_AWARD).where(SCHOLARSHIPS_AWARD.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_AWARD.ID.eq(id)).fetchOptional(JooqAwardRepository::toAward);
    }

    @Override
    public Optional<ScholarshipAward> findForUpdate(InstitutionId institution, UUID id) {
        return dsl.selectFrom(SCHOLARSHIPS_AWARD).where(SCHOLARSHIPS_AWARD.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_AWARD.ID.eq(id)).forUpdate().fetchOptional(JooqAwardRepository::toAward);
    }

    @Override
    public void advanceStatus(InstitutionId institution, UUID id, AwardStatus to) {
        ScholarshipsWrites.writing(() -> {
            int updated = dsl.update(SCHOLARSHIPS_AWARD).set(SCHOLARSHIPS_AWARD.STATUS, to.code())
                    .where(SCHOLARSHIPS_AWARD.INSTITUTION_ID.eq(institution.value())).and(SCHOLARSHIPS_AWARD.ID.eq(id))
                    .execute();
            if (updated != 1) {
                // The caller holds the row locked: anything but one row is a defect, never a decision that silently did nothing.
                throw new IllegalStateException("the status of an award moves in exactly one row");
            }
        });
    }

    @Override
    public Set<UUID> withApprovedAwards(InstitutionId institution, Collection<UUID> scholarshipIds) {
        if (scholarshipIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.selectDistinct(SCHOLARSHIPS_AWARD.SCHOLARSHIP_ID).from(SCHOLARSHIPS_AWARD)
                .where(SCHOLARSHIPS_AWARD.INSTITUTION_ID.eq(institution.value()))
                .and(SCHOLARSHIPS_AWARD.SCHOLARSHIP_ID.in(scholarshipIds))
                .and(SCHOLARSHIPS_AWARD.STATUS.in(AwardStatus.APPROVED.code(), AwardStatus.REVOKED.code()))
                .fetch(SCHOLARSHIPS_AWARD.SCHOLARSHIP_ID));
    }

    @Override
    public Page<ScholarshipAward> list(InstitutionId institution, AwardFilter filter, PageRequest page) {
        Condition where = SCHOLARSHIPS_AWARD.INSTITUTION_ID.eq(institution.value());
        if (filter.studentId() != null) {
            where = where.and(SCHOLARSHIPS_AWARD.STUDENT_ID.eq(filter.studentId()));
        }
        if (filter.status() != null) {
            where = where.and(SCHOLARSHIPS_AWARD.STATUS.eq(filter.status()));
        }
        if (filter.scholarshipId() != null) {
            where = where.and(SCHOLARSHIPS_AWARD.SCHOLARSHIP_ID.eq(filter.scholarshipId()));
        }
        if (filter.academicYearId() != null) {
            where = where.and(SCHOLARSHIPS_AWARD.ACADEMIC_YEAR_ID.eq(filter.academicYearId()));
        }
        long total = dsl.selectCount().from(SCHOLARSHIPS_AWARD).where(where).fetchOne(0, long.class);
        List<ScholarshipAward> rows = dsl.selectFrom(SCHOLARSHIPS_AWARD).where(where)
                .orderBy(order(page), SCHOLARSHIPS_AWARD.ID.asc()).limit(page.size()).offset(page.offset())
                .fetch(JooqAwardRepository::toAward);
        return new Page<>(rows, total, page.page(), page.size());
    }

    /** The sort field was checked against {@code AwardFilter.SORT_FIELDS} by the request; anything else is a defect. */
    private static OrderField<?> order(PageRequest page) {
        var field = switch (page.sortField()) {
            case "requestedAt" -> SCHOLARSHIPS_AWARD.REQUESTED_AT;
            case "validFrom" -> SCHOLARSHIPS_AWARD.VALID_FROM;
            case "validTo" -> SCHOLARSHIPS_AWARD.VALID_TO;
            default -> throw new IllegalArgumentException("unknown sort field");
        };
        return page.ascending() ? field.asc() : field.desc();
    }

    private static ScholarshipAward toAward(Record row) {
        return new ScholarshipAward(row.get(SCHOLARSHIPS_AWARD.ID), row.get(SCHOLARSHIPS_AWARD.SCHOLARSHIP_ID),
                row.get(SCHOLARSHIPS_AWARD.STUDENT_ID), row.get(SCHOLARSHIPS_AWARD.ACADEMIC_YEAR_ID),
                row.get(SCHOLARSHIPS_AWARD.VALID_FROM), row.get(SCHOLARSHIPS_AWARD.VALID_TO),
                AwardStatus.ofCode(row.get(SCHOLARSHIPS_AWARD.STATUS)).orElseThrow(),
                row.get(SCHOLARSHIPS_AWARD.REQUESTED_BY), ScholarshipsWrites.fromDatabase(row.get(SCHOLARSHIPS_AWARD.REQUESTED_AT)));
    }
}
